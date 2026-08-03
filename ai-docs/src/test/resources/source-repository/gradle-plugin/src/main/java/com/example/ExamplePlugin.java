package com.example;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

public abstract class ExamplePlugin implements Plugin<Project> {
    @Override
    public final void apply(Project project) {}
}
