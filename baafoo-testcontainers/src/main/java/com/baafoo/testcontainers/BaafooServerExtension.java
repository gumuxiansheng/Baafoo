package com.baafoo.testcontainers;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public class BaafooServerExtension extends BaafooServerContainer
        implements BeforeAllCallback, AfterAllCallback {

    public BaafooServerExtension() {
        super();
    }

    public BaafooServerExtension(String dockerImageName) {
        super(dockerImageName);
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        start();
    }

    @Override
    public void afterAll(ExtensionContext context) {
        stop();
    }
}
