package com.acme.orderplatform.recon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ReconciliationBatchApplication {
    public static void main(String[] args) {
        // Exit code propagates to the CronJob / scheduler (Autosys, Control-M) so failures alert.
        System.exit(SpringApplication.exit(SpringApplication.run(ReconciliationBatchApplication.class, args)));
    }
}
