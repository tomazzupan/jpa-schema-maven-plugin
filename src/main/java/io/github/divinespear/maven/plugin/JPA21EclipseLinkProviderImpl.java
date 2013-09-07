package io.github.divinespear.maven.plugin;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.persistence.Persistence;

import org.eclipse.persistence.config.PersistenceUnitProperties;

class JPA21EclipseLinkProviderImpl
        extends BaseProviderImpl {

    public JPA21EclipseLinkProviderImpl() {
        super("eclipselink_2.1");
    }

    private static final String
            TARGET_DATABASE = "database",
            TARGET_SCRIPT = "script",
            TARGET_BOTH = "both";
    private static final List<String>
            TARGET_DATABASE_LIST = Arrays.asList(TARGET_DATABASE, TARGET_BOTH),
            TARGET_SCRIPT_LIST = Arrays.asList(TARGET_SCRIPT, TARGET_BOTH);

    @Override
    protected void doExecute(JpaSchemaGeneratorMojo mojo) {
        final String target = mojo.getTarget().toLowerCase();
        String databaseMode = PersistenceUnitProperties.SCHEMA_GENERATION_NONE_ACTION;
        if (TARGET_DATABASE_LIST.contains(target)) {
            databaseMode = mojo.getMode();
        }
        String scriptMode = PersistenceUnitProperties.SCHEMA_GENERATION_NONE_ACTION;
        if (TARGET_SCRIPT_LIST.contains(target)) {
            scriptMode = mojo.getMode();
        }

        Map<String, String> map = new HashMap<String, String>();
        // persistence.xml
        map.put(PersistenceUnitProperties.ECLIPSELINK_PERSISTENCE_XML, mojo.getPersistenceXml());
        // mode
        map.put(PersistenceUnitProperties.SCHEMA_GENERATION_DATABASE_ACTION, databaseMode);
        map.put(PersistenceUnitProperties.SCHEMA_GENERATION_SCRIPTS_ACTION, scriptMode);
        // output files
        map.put(PersistenceUnitProperties.SCHEMA_GENERATION_SCRIPTS_CREATE_TARGET,
                new File(mojo.getOutputDirectory(), mojo.getCreateOutputFileName()).toURI().toString());
        map.put(PersistenceUnitProperties.SCHEMA_GENERATION_SCRIPTS_DROP_TARGET,
                new File(mojo.getOutputDirectory(), mojo.getDropOutputFileName()).toURI().toString());
        // database options
        map.put(PersistenceUnitProperties.JDBC_DRIVER, mojo.getJdbcDriver());
        map.put(PersistenceUnitProperties.JDBC_URL, mojo.getJdbcUrl());
        map.put(PersistenceUnitProperties.JDBC_USER, mojo.getJdbcUser());
        map.put(PersistenceUnitProperties.JDBC_PASSWORD, mojo.getJdbcPassword());
        // database emulation options
        map.put(PersistenceUnitProperties.SCHEMA_DATABASE_PRODUCT_NAME, mojo.getDatabaseProductName());
        map.put(PersistenceUnitProperties.SCHEMA_DATABASE_MAJOR_VERSION, mojo.getDatabaseMajorVersion());
        map.put(PersistenceUnitProperties.SCHEMA_DATABASE_MINOR_VERSION, mojo.getDatabaseMinorVersion());

        Persistence.generateSchema(mojo.getPersistenceUnitName(), this.removeNullValuesFromMap(map));
    }
}
