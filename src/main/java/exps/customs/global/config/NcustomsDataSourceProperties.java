package exps.customs.global.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "ncustoms.datasource")
public class NcustomsDataSourceProperties {

    private boolean enabled = false;
    private String url;
    private String username;
    private String password;
    private String driverClassName = "com.mysql.cj.jdbc.Driver";
    private String connectionInitSql = "SET NAMES euckr";
}

