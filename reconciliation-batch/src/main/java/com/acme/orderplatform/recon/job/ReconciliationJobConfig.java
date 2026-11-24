package com.acme.orderplatform.recon.job;

import com.acme.orderplatform.recon.extract.ExtractPublisher;
import com.acme.orderplatform.recon.extract.ExtractTasklet;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.item.database.support.PostgresPagingQueryProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * orderReconciliationJob(businessDate)
 *   step 1  reconcileOrders  - partitioned, chunk-oriented: read orders, classify, write results
 *   step 2  publishExtract   - write the day's extract + manifest (counts, control totals, checksum) to S3
 */
@Configuration
public class ReconciliationJobConfig {

    @Bean
    Job orderReconciliationJob(JobRepository repo, Step reconcileOrders, Step publishExtract) {
        return new JobBuilder("orderReconciliationJob", repo)
                .start(reconcileOrders)
                .next(publishExtract)
                .build();
    }

    @Bean
    Step reconcileOrders(JobRepository repo, Step reconcileBucket, TaskExecutor batchTaskExecutor,
                         @Value("${app.batch.grid-size:4}") int gridSize) {
        return new StepBuilder("reconcileOrders", repo)
                .partitioner("reconcileBucket", new HashPartitioner())
                .step(reconcileBucket)
                .gridSize(gridSize)
                .taskExecutor(batchTaskExecutor)
                .build();
    }

    @Bean
    Step reconcileBucket(JobRepository repo, PlatformTransactionManager tx,
                         JdbcPagingItemReader<OrderSnapshot> ordersReader,
                         ReconciliationProcessor reconciliationProcessor,
                         JdbcBatchItemWriter<ReconciliationRecord> resultWriter,
                         @Value("${app.batch.chunk-size:500}") int chunkSize) {
        return new StepBuilder("reconcileBucket", repo)
                .<OrderSnapshot, ReconciliationRecord>chunk(chunkSize, tx)
                .reader(ordersReader)
                .processor(reconciliationProcessor)
                .writer(resultWriter)
                .faultTolerant()
                .retry(TransientDataAccessException.class)
                .retryLimit(3)
                .build();
    }

    @Bean
    @StepScope
    JdbcPagingItemReader<OrderSnapshot> ordersReader(@Qualifier("ordersDataSource") DataSource orders,
                                                     @Value("#{jobParameters['businessDate']}") String businessDate,
                                                     @Value("#{stepExecutionContext['partition']}") Integer partition,
                                                     @Value("#{stepExecutionContext['gridSize']}") Integer gridSize,
                                                     @Value("${app.batch.chunk-size:500}") int pageSize) {
        LocalDate day = LocalDate.parse(businessDate);
        var query = new PostgresPagingQueryProvider();
        query.setSelectClause("""
                select o.id, o.customer_id, o.status, o.total_amount, o.currency, o.created_at, o.updated_at,
                       (select count(*) from outbox_event e where e.message_key = o.id::text and e.published_at is null) as unpublished
                """);
        query.setFromClause("from orders o");
        query.setWhereClause("""
                where o.created_at >= :from and o.created_at < :to
                  and mod(abs(hashtext(o.id::text)), :gridSize) = :partition
                """);
        query.setSortKeys(Map.of("id", Order.ASCENDING));

        return new JdbcPagingItemReaderBuilder<OrderSnapshot>()
                .name("ordersReader-" + partition)
                .dataSource(orders)
                .queryProvider(query)
                .parameterValues(Map.of(
                        "from", Timestamp.from(day.atStartOfDay(ZoneOffset.UTC).toInstant()),
                        "to", Timestamp.from(day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()),
                        "gridSize", gridSize,
                        "partition", partition))
                .pageSize(pageSize)
                .rowMapper((rs, i) -> new OrderSnapshot(
                        rs.getObject("id", UUID.class), rs.getString("customer_id"), rs.getString("status"),
                        rs.getBigDecimal("total_amount"), rs.getString("currency"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                        rs.getInt("unpublished")))
                .build();
    }

    @Bean
    @StepScope
    ReconciliationProcessor reconciliationProcessor(@Value("#{stepExecution.jobExecutionId}") Long jobExecutionId,
                                                    @Value("#{jobParameters['businessDate']}") String businessDate,
                                                    @Value("${app.batch.stuck-order-sla:PT30M}") Duration sla) {
        return new ReconciliationProcessor(jobExecutionId, LocalDate.parse(businessDate), sla, Clock.systemUTC());
    }

    /** Upsert keyed by (business_date, order_id): re-running a day is idempotent, never duplicates. */
    @Bean
    JdbcBatchItemWriter<ReconciliationRecord> resultWriter(DataSource recon) {
        return new JdbcBatchItemWriterBuilder<ReconciliationRecord>()
                .dataSource(recon)
                .sql("""
                        insert into reconciliation_result
                            (business_date, order_id, status, total_amount, currency, issue, job_execution_id, reconciled_at)
                        values (?, ?, ?, ?, ?, ?, ?, now())
                        on conflict (business_date, order_id) do update set
                            status = excluded.status, total_amount = excluded.total_amount, currency = excluded.currency,
                            issue = excluded.issue, job_execution_id = excluded.job_execution_id, reconciled_at = now()
                        """)
                .itemPreparedStatementSetter((r, ps) -> {
                    ps.setObject(1, r.businessDate());
                    ps.setObject(2, r.orderId());
                    ps.setString(3, r.status());
                    ps.setBigDecimal(4, r.totalAmount());
                    ps.setString(5, r.currency());
                    ps.setString(6, r.issue().name());
                    ps.setLong(7, r.jobExecutionId());
                })
                .build();
    }

    @Bean
    Step publishExtract(JobRepository repo, PlatformTransactionManager tx, ExtractTasklet extractTasklet) {
        return new StepBuilder("publishExtract", repo).tasklet(extractTasklet, tx).build();
    }

    @Bean
    ExtractTasklet extractTasklet(NamedParameterJdbcTemplate reconJdbc, ExtractPublisher publisher,
                                  @Value("${spring.application.name}") String source) {
        return new ExtractTasklet(reconJdbc, publisher, source);
    }

    @Bean
    NamedParameterJdbcTemplate reconJdbc(DataSource recon) {
        return new NamedParameterJdbcTemplate(recon);
    }

    @Bean
    TaskExecutor batchTaskExecutor(@Value("${app.batch.grid-size:4}") int gridSize) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(gridSize);
        executor.setMaxPoolSize(gridSize);
        executor.setThreadNamePrefix("recon-");
        executor.initialize();
        return executor;
    }
}
