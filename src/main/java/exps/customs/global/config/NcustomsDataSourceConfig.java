package exps.customs.global.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties(NcustomsDataSourceProperties.class)
@ConditionalOnProperty(prefix = "ncustoms.datasource", name = "enabled", havingValue = "true")
public class NcustomsDataSourceConfig {

    @Bean(name = "ncustomsDataSource")
    public DataSource ncustomsDataSource(NcustomsDataSourceProperties properties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(properties.getUrl());
        dataSource.setUsername(properties.getUsername());
        dataSource.setPassword(properties.getPassword());
        dataSource.setDriverClassName(properties.getDriverClassName());
        dataSource.setConnectionInitSql(properties.getConnectionInitSql());
        dataSource.setPoolName("ncustoms-pool");
        dataSource.setMaximumPoolSize(3);
        dataSource.setMinimumIdle(1);
        dataSource.setConnectionTimeout(10_000);
        return dataSource;
    }

    @Bean(name = "ncustomsJdbcTemplate")
    public JdbcTemplate ncustomsJdbcTemplate(@Qualifier("ncustomsDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}

