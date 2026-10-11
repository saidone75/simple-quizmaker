package org.saidone.quizmaker.service;

import org.springframework.boot.test.context.SpringBootTest;

/** Run the same persistence, access and rendering checks against the production database engine. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:file:badge-integration?mode=memory&cache=shared",
        "spring.datasource.driver-class-name=org.sqlite.JDBC",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1",
        "spring.datasource.hikari.connection-init-sql=PRAGMA foreign_keys=ON",
        "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.liquibase.enabled=true",
        "app.turnstile.enabled=false",
        "app.image-cleanup.enabled=false",
        "app.db-backup.enabled=false"
})
class StudentBadgeSqliteIntegrationTest extends StudentBadgeIntegrationTest { }
