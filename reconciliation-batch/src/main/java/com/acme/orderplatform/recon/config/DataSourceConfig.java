package com.acme.orderplatform.recon.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;


/**
 * Two data sources on purpose:
 * <ul>
 *   <li>primary = recondb: owned by this job (Spring Batch metadata, reconciliation results).</li>
 *   <li>orders  = read-only connection to the order DB (an RDS read replica in AWS), so the batch
 *       never adds load to, or writes into, another service's primary database.</li>
 * </ul>
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    DataSourceProperties reconDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource dataSource(DataSourceProperties props) {
        return props.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @ConfigurationProperties("app.orders-datasource")
    DataSourceProperties ordersDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("app.orders-datasource.hikari")
    HikariDataSource ordersDataSource(@Qualifier("ordersDataSourceProperties") DataSourceProperties props) {
        HikariDataSource ds = props.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        ds.setReadOnly(true);
        return ds;
    }
}
