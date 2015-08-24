package io.cloudsoft.terraform;

import java.io.InputStream;

import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.entity.java.JavaSoftwareProcessSshDriver;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.util.core.ResourceUtils;
import org.apache.brooklyn.util.stream.KnownSizeInputStream;
import org.apache.brooklyn.util.text.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;

public class TerraformSshDriver extends JavaSoftwareProcessSshDriver implements TerraformDriver {

    private static final Logger log = LoggerFactory.getLogger(TerraformSshDriver.class);

    public TerraformSshDriver(EntityLocal entity, SshMachineLocation machine) {
        super(entity, machine);
    }

    @Override
    public boolean isRunning() {
        return true;
    }

    @Override
    protected String getLogFileLocation() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void stop() {
        // TODO Auto-generated method stub
    }

    @Override
    public void install() {
        log.info("Terraform CLI assumed to be installed -- taking no action.");
    }

    @Override
    public void customize() {
      //Create the directory
        newScript(CUSTOMIZING).execute();

        boolean hasConfiguration = copyConfiguration();
        if (!hasConfiguration)
            throw new IllegalStateException("No Terraform configuration could be resolved.");
    }

    @Override
    public void launch() {
        // TODO Auto-generated method stub
    }

    private boolean copyConfiguration() {
        Optional<? extends InputStream> configuration = getConfiguration();
        if (!configuration.isPresent())
            return false;

        getMachine().copyTo(configuration.get(), getConfigurationFilePath());
        return true;
    }

    private Optional<? extends InputStream> getConfiguration() {
        String configurationUrl = entity.getConfig(TerraformConfiguration.CONFIGURATION_URL);
        if (!Strings.isBlank(configurationUrl))
            return Optional.of(new ResourceUtils(entity).getResourceFromUrl(configurationUrl));

        String configurationContents = entity.getConfig(TerraformConfiguration.CONFIGURATION_CONTENTS);
        if (!Strings.isBlank(configurationContents))
            return Optional.of(KnownSizeInputStream.of(configurationContents));

        return Optional.absent();
    }

    private String getConfigurationFilePath() {
        return getRunDir() + "/configuration.tf";
    }
}
