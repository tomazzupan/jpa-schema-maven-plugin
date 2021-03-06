package io.github.divinespear.maven.plugin.stub;

import java.io.File;

public class Issue13ProjectCrLfStub extends AbstractProjectStub {
    @Override
    public String getProjectPath() {
        return "issue-13";
    }

    @Override
    protected File getPomFile() {
        return new File (getBasedir(), "pom-crlf.xml");
    }
}
