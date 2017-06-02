package io.github.divinespear.maven.plugin;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

@SuppressWarnings("JUnit4AnnotatedMethodInJUnit3TestCase")
public class IssueTz1Test
        extends AbstractSchemaGeneratorMojoTest {

    @Before
    @Override
    protected void setUp() throws Exception {
        super.setUp();
    }

    @After
    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * Simple schema generation test for script using Hibernate
     *
     * @throws Exception if any exception raises
     */
    @Test
    public void testGenerateScriptUsingEclipseLink() throws Exception {
        final File pomFile = this.getPomFile("target/test-classes/unit/issue-tz1");

        this.compileJpaModelSources(pomFile);
        JpaSchemaGeneratorMojo mojo = this.executeSchemaGeneration(pomFile);

        File createScriptFile = mojo.getCreateOutputFile();
        assertThat("create script should be generated.", createScriptFile.exists(), is(true));

        final String expectCreate = readResourceAsString("/unit/issue-tz1/expected-create.txt");
        assertThat(this.readFileAsString(createScriptFile), is(expectCreate));

        File dropScriptFile = mojo.getDropOutputFile();
        assertThat("drop script should be generated.", dropScriptFile.exists(), is(true));

        final String expectDrop = readResourceAsString("/unit/issue-tz1/expected-drop.txt");
        assertThat(this.readFileAsString(dropScriptFile), is(expectDrop));
    }

}
