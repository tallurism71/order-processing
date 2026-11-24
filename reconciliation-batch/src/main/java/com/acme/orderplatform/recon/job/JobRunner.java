package com.acme.orderplatform.recon.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Usage: java -jar reconciliation-batch.jar [--businessDate=2026-11-23] [--rerun]
 *
 * businessDate is the identifying job parameter: a FAILED run for a date restarts from its last
 * checkpoint; a COMPLETED date is not re-run unless --rerun is given (then results are upserted).
 */
@Component
public class JobRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final JobLauncher launcher;
    private final Job job;
    private int exitCode = 0;

    public JobRunner(JobLauncher launcher, Job orderReconciliationJob) {
        this.launcher = launcher;
        this.job = orderReconciliationJob;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<String> dates = args.getOptionValues("businessDate");
        LocalDate businessDate = dates == null || dates.isEmpty()
                ? LocalDate.now(ZoneOffset.UTC).minusDays(1)
                : LocalDate.parse(dates.get(0));

        var params = new JobParametersBuilder().addString("businessDate", businessDate.toString());
        if (args.containsOption("rerun")) {
            params.addLong("run.id", System.currentTimeMillis());
        }
        try {
            JobExecution execution = launcher.run(job, params.toJobParameters());
            log.info("Job {} for {} finished with {}", job.getName(), businessDate, execution.getStatus());
            exitCode = execution.getStatus() == BatchStatus.COMPLETED ? 0 : 1;
        } catch (JobInstanceAlreadyCompleteException e) {
            log.info("{} already reconciled; pass --rerun to reprocess", businessDate);
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
