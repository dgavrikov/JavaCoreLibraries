package io.github.dgavrikov.core.database.config;

import com.zaxxer.hikari.util.IsolationLevel;
import io.github.dgavrikov.core.config.YamlPropertyLoaderFactory;
import io.github.dgavrikov.core.database.properties.DatabaseProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.orm.jpa.JpaProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.PropertySource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;

@Configuration(proxyBeanMethods = false)
@PropertySource(value = "classpath:default_database.yml", factory = YamlPropertyLoaderFactory.class)
@EnableConfigurationProperties({DatabaseProperties.class, JpaProperties.class})
@EnableJpaRepositories(
        basePackages = "${spring.datasource.properties.repository-packages}",
        entityManagerFactoryRef = "postgresEntityManager",
        transactionManagerRef = "postgresTransactionManager"
)
@EnableTransactionManagement
public class PostgresConfig {

    @Bean
    @Primary
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    public HikariDataSource postgresDataSource() { return  new HikariDataSource();}

    @Bean
    @Primary
    public LocalContainerEntityManagerFactoryBean postgresEntityManager(
            @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
            EntityManagerFactoryBuilder builder,
        DataSource dataSource,
        JpaProperties jpaProperties,
        DatabaseProperties dbProps){
        return builder
                .dataSource(dataSource)
                .packages(dbProps.getRepositoryPackages().toArray(new String[0]))
                .persistenceUnit("postgres")
                .properties(jpaProperties.getProperties())
                .build();
    }

    @Bean
    @Primary
    public PlatformTransactionManager postgresTransactionManager(
            @Qualifier("postgresEntityManager") LocalContainerEntityManagerFactoryBean emFactory,
            DatabaseProperties dbProps
    ){
        JpaTransactionManager txManager = new JpaTransactionManager();
        txManager.setEntityManagerFactory(emFactory.getObject());
        txManager.setDefaultTimeout(dbProps.getTransactionTimeout());
        return txManager;
    }

    @Bean
    public TransactionTemplate transactionTemplate(
            PlatformTransactionManager postgresTransactionManager,
            HikariDataSource postgresDataSource) {
        var definition = new DefaultTransactionDefinition();
        var isolationName = postgresDataSource.getTransactionIsolation();

        if(isolationName != null && !isolationName.equals("DEFAULT")) {
            try {
                int isolationLevelCode = IsolationLevel.valueOf(isolationName).getLevelId();
                definition.setIsolationLevel(isolationLevelCode);
            } catch (IllegalArgumentException e) {
                definition.setIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);
            }
        }
        return new TransactionTemplate(postgresTransactionManager, definition);
    }
}
