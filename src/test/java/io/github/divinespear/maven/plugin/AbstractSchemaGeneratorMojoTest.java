package io.github.divinespear.maven.plugin;

import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugin.testing.AbstractMojoTestCase;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.invoker.*;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.ArtifactTypeRegistry;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.util.filter.ScopeDependencyFilter;

import java.io.*;
import java.net.MalformedURLException;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertThat;

abstract class AbstractSchemaGeneratorMojoTest
        extends AbstractMojoTestCase {

    static final String LINE_SEPARATOR = System.getProperty("line.separator");

    private static final String POM_FILENAME = "pom.xml";

    protected File getPomFile(String path) {
        return this.getPomFile(path, POM_FILENAME);
    }

    protected File getPomFile(String path,
                              String pomFileName) {
        return new File(new File(getBasedir(), path), pomFileName);
    }

    protected void compileJpaModelSources(File pomFile) throws MavenInvocationException {
        Properties properties = new Properties();
        properties.setProperty("plugin.version", System.getProperty("plugin.version"));

        InvocationRequest request = new DefaultInvocationRequest();
        request.setPomFile(pomFile);
        request.setGoals(Collections.singletonList("compile"));
        request.setProperties(properties);

        Invoker invoker = new DefaultInvoker();
        invoker.execute(request);
    }

    JpaSchemaGeneratorMojo getGenerateMojo(File pomFile) throws Exception {
        return (JpaSchemaGeneratorMojo) lookupMojo("generate", pomFile);
    }

    protected JpaSchemaGeneratorMojo executeSchemaGeneration(File pomFile) throws Exception {
        // create mojo
        JpaSchemaGeneratorMojo mojo = getGenerateMojo(pomFile);
        assertThat(mojo, notNullValue(JpaSchemaGeneratorMojo.class));

        // setSession
        MavenProject project = (MavenProject) getVariableValueFromObject(mojo, "project");
        MavenSession session = newMavenSession(project);
        setVariableValueToObject(mojo, "session", session);

        // setup pluginDescriptor
        MojoExecution mojoExecution = newMojoExecution("generate");
        PluginDescriptor pluginDescriptor = mojoExecution.getMojoDescriptor().getPluginDescriptor();
        pluginDescriptor.setPlugin(project.getPlugin(pluginDescriptor.getGroupId() + ":" + pluginDescriptor.getArtifactId()));
        setVariableValueToObject(mojo, "pluginDescriptor", pluginDescriptor);

        // setup Repository System Session
        RepositorySystem repositorySystem = (RepositorySystem) getVariableValueFromObject(mojo, "repoSystem");
        setupRepositorySession(session, repositorySystem);

        // resolve project dependencies
        resolveProjectDependencies(session, repositorySystem);

        // execute
        mojo.execute();


        return mojo;
    }

    private void setupRepositorySession(MavenSession session, RepositorySystem repositorySystem) {
        LocalRepository localRepo = new LocalRepository("target/test-classes/unit/local-repo");

        DefaultRepositorySystemSession repositorySession = (DefaultRepositorySystemSession) session.getRepositorySession();
        repositorySession.setLocalRepositoryManager(repositorySystem.newLocalRepositoryManager(repositorySession, localRepo));
    }

    private void resolveProjectDependencies(MavenSession session, RepositorySystem repositorySystem) throws DependencyResolutionException, DependencyResolutionRequiredException, MalformedURLException {
        MavenProject project = session.getCurrentProject();
        RepositorySystemSession repositorySession = session.getRepositorySession();
        ArtifactTypeRegistry stereotypes = repositorySession.getArtifactTypeRegistry();

        CollectRequest collectRequest = new CollectRequest();
        collectRequest.setRootArtifact(RepositoryUtils.toArtifact(project.getArtifact()));
        collectRequest.setRequestContext("project");
        collectRequest.setRepositories(project.getRemoteProjectRepositories());
        for (Dependency dependency : project.getDependencies())
            collectRequest.addDependency(RepositoryUtils.toDependency(dependency, stereotypes));


        DependencyFilter dependencyFilter = new ScopeDependencyFilter(Artifact.SCOPE_TEST);
        DependencyRequest request = new DependencyRequest(collectRequest, dependencyFilter);
        List<String> compileClasspathElements = project.getCompileClasspathElements();
        DependencyResult dependencyResult = repositorySystem.resolveDependencies(repositorySession, request);
        for (ArtifactResult artifactResult : dependencyResult.getArtifactResults())
            compileClasspathElements.add(artifactResult.getArtifact().getFile().toString());

    }

    protected String readResourceAsString(String name) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream(name)) {
            StringBuilder builder = new StringBuilder();
            InputStreamReader reader = new InputStreamReader(stream);
            char[] buf = new char[4096];
            while (reader.ready()) {
                int len = reader.read(buf);
                builder.append(buf, 0, len);
            }
            return builder.toString().replaceAll("\n", LINE_SEPARATOR);
        }
    }

    protected String readFileAsString(File file) throws IOException {
        try (FileReader reader = new FileReader(file)) {
            StringBuilder builder = new StringBuilder();
            char[] buf = new char[4096];
            while (reader.ready()) {
                int len = reader.read(buf);
                builder.append(buf, 0, len);
            }
            return builder.toString();
        }
    }

}
