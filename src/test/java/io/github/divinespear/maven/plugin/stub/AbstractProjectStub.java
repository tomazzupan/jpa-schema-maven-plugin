package io.github.divinespear.maven.plugin.stub;

import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.testing.stubs.MavenProjectStub;
import org.codehaus.plexus.util.ReaderFactory;
import org.eclipse.aether.repository.RemoteRepository;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Tomaz Zupan
 */
public abstract class AbstractProjectStub extends MavenProjectStub {
    private static final Pattern expressionPattern = Pattern.compile("\\$\\{(.*)\\}");

    private List<String> compileClasspathElements;

    public AbstractProjectStub() {
        MavenXpp3Reader pomReader = new MavenXpp3Reader();
        Model model;
        try {
            model = pomReader.read(ReaderFactory.newXmlReader(getPomFile()));
            setModel(model);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        setGroupId(model.getGroupId());
        setArtifactId(model.getArtifactId());
        setVersion(model.getVersion());
        setArtifact(new DefaultArtifact(getGroupId(), getArtifactId(), getVersion(), null, "jar", null, new DefaultArtifactHandler()));

        // replace expression versions with real
        for (Dependency dependency : model.getDependencies())
            dependency.setVersion(getVersion(dependency.getVersion()));
        for (Plugin plugin : model.getBuild().getPlugins())
            for (Dependency dependency : plugin.getDependencies())
                dependency.setVersion(getVersion(dependency.getVersion()));

        setBuild(model.getBuild());

    }

    public abstract String getProjectPath();

    @Override
    public File getBasedir() {
        return new File(super.getBasedir(), "target/test-classes/unit/" + getProjectPath());
    }

    @Override
    public List<RemoteRepository> getRemoteProjectRepositories() {
        List<RemoteRepository> remoteRepositories = new ArrayList<>();
        RemoteRepository centralRepository = new RemoteRepository.Builder("central", "default", "http://repo.maven.apache.org/maven2").build();
        remoteRepositories.add(centralRepository);

        return remoteRepositories;
    }

    @Override
    public List<String> getCompileClasspathElements() throws DependencyResolutionRequiredException {
        if (compileClasspathElements == null) {
            compileClasspathElements = new ArrayList<>();

            String outputDirectory = getBuild().getOutputDirectory();
            if (outputDirectory != null)
                compileClasspathElements.add(getBasedir() + "/" + outputDirectory);
        }

        return compileClasspathElements;
    }

    @Override
    public List<String> getTestClasspathElements() throws DependencyResolutionRequiredException {
        List<String> testClasspathElements = new ArrayList<>();
        String testOutputDirectory = getBuild().getTestOutputDirectory();
        if (testOutputDirectory != null)
            testClasspathElements.add(testOutputDirectory);

        return testClasspathElements;
    }

    @Override
    public List<Dependency> getDependencies() {
        return getModel().getDependencies();
    }

    protected File getPomFile() {
        return new File(getBasedir(), "pom.xml");
    }

    private String getVersion(String rawVersion) {
        Matcher matcher = expressionPattern.matcher(rawVersion);
        matcher.reset();
        boolean result = matcher.find();
        if (result) {
            StringBuffer sb = new StringBuffer();
            do {
                matcher.appendReplacement(sb, getProjectProperty(matcher.group(1)));
                result = matcher.find();
            } while (result);
            matcher.appendTail(sb);

            return sb.toString();
        }
        return rawVersion;
    }

    private String getProjectProperty(String key) {
        return getModel().getProperties().getProperty(key);
    }
}
