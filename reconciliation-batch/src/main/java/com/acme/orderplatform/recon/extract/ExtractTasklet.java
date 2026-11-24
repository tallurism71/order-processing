package com.acme.orderplatform.recon.extract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Publishes the day's reconciled orders for downstream consumers (finance, data lake) with a
 * manifest that makes the delivery verifiable:
 *
 * <ul>
 *   <li>data file first, manifest last: the manifest's presence means "the data is complete".</li>
 *   <li>record count + control total + SHA-256 let the consumer prove it got everything, unaltered.</li>
 *   <li>lineage: source system, job execution id, schema version, generated-at timestamp.</li>
 * </ul>
 * The file is streamed to disk while hashing, so memory use stays flat for millions of rows.
 */
public class ExtractTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(ExtractTasklet.class);
    private static final String SCHEMA_VERSION = "1";

    private final NamedParameterJdbcTemplate jdbc;
    private final ExtractPublisher publisher;
    private final String source;
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public ExtractTasklet(NamedParameterJdbcTemplate jdbc, ExtractPublisher publisher, String source) {
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.source = source;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        var stepExecution = chunkContext.getStepContext().getStepExecution();
        long jobExecutionId = stepExecution.getJobExecutionId();
        LocalDate businessDate = LocalDate.parse(stepExecution.getJobParameters().getString("businessDate"));
        String prefix = "orders/reconciliation/business_date=" + businessDate + "/run=" + jobExecutionId + "/";

        Path dataFile = Files.createTempFile("orders-recon-", ".csv");
        try {
            Totals totals = writeCsv(businessDate, dataFile);
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("dataset", "orders-reconciliation");
            manifest.put("schemaVersion", SCHEMA_VERSION);
            manifest.put("businessDate", businessDate.toString());
            manifest.put("sourceSystem", source);
            manifest.put("jobExecutionId", jobExecutionId);
            manifest.put("generatedAt", Instant.now().toString());
            manifest.put("dataFile", prefix + "orders.csv");
            manifest.put("recordCount", totals.count);
            manifest.put("controlTotalAmount", totals.amount);
            manifest.put("sha256", totals.sha256);
            manifest.put("countByStatus", totals.byStatus);
            manifest.put("countByIssue", totals.byIssue);

            Map<String, String> meta = Map.of("record-count", String.valueOf(totals.count), "sha256", totals.sha256,
                    "business-date", businessDate.toString(), "schema-version", SCHEMA_VERSION);
            String dataUri = publisher.publish(dataFile, prefix + "orders.csv", "text/csv", meta);

            Path manifestFile = Files.createTempFile("orders-recon-manifest-", ".json");
            try {
                json.writeValue(manifestFile.toFile(), manifest);
                publisher.publish(manifestFile, prefix + "_manifest.json", "application/json", meta);
            } finally {
                Files.deleteIfExists(manifestFile);
            }

            jdbc.update("""
                    insert into extract_audit (business_date, job_execution_id, record_count, control_total, sha256, location, published_at)
                    values (:date, :job, :count, :total, :sha, :location, now())
                    """, Map.of("date", businessDate, "job", jobExecutionId, "count", totals.count,
                    "total", totals.amount, "sha", totals.sha256, "location", dataUri));

            contribution.incrementWriteCount(totals.count);
            long issues = totals.byIssue.entrySet().stream().filter(e -> !"NONE".equals(e.getKey()))
                    .mapToLong(Map.Entry::getValue).sum();
            if (issues > 0) {
                log.warn("Reconciliation {} found {} order(s) with issues: {}", businessDate, issues, totals.byIssue);
            }
            log.info("Published extract {} records={} controlTotal={} sha256={}", dataUri, totals.count, totals.amount, totals.sha256);
            return RepeatStatus.FINISHED;
        } finally {
            Files.deleteIfExists(dataFile);
        }
    }

    private Totals writeCsv(LocalDate businessDate, Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Totals totals = new Totals();
        try (var out = new BufferedWriter(new OutputStreamWriter(
                new DigestOutputStream(Files.newOutputStream(file), digest), StandardCharsets.UTF_8))) {
            out.write("order_id,status,total_amount,currency,issue\n");
            jdbc.query("""
                            select order_id, status, total_amount, currency, issue
                            from reconciliation_result where business_date = :date order by order_id
                            """, Map.of("date", businessDate),
                    rs -> {
                        try {
                            BigDecimal amount = rs.getBigDecimal("total_amount");
                            String status = rs.getString("status");
                            String issue = rs.getString("issue");
                            out.write(rs.getString("order_id") + "," + status + "," + amount.toPlainString() + ","
                                    + rs.getString("currency") + "," + issue + "\n");
                            totals.count++;
                            totals.amount = totals.amount.add(amount);
                            totals.byStatus.merge(status, 1L, Long::sum);
                            totals.byIssue.merge(issue, 1L, Long::sum);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
        totals.sha256 = HexFormat.of().formatHex(digest.digest());
        return totals;
    }

    private static final class Totals {
        long count;
        BigDecimal amount = BigDecimal.ZERO;
        String sha256;
        final Map<String, Long> byStatus = new TreeMap<>();
        final Map<String, Long> byIssue = new TreeMap<>();
    }
}
