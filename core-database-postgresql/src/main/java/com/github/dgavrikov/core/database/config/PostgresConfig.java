package com.github.dgavrikov.core.database.config;

import com.github.dgavrikov.core.config.YamlPropertyLoaderFactory;
import com.github.dgavrikov.core.database.properties.DatabaseProperties;
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

import javax.sql.DataSource;

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
}
