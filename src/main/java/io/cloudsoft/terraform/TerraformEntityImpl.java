package io.cloudsoft.terraform;

import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.entity.stock.BasicStartableImpl;
import org.apache.brooklyn.util.text.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

import static io.cloudsoft.terraform.TerraformCommons.convertConfigToTerraformEnvVar;

public class TerraformEntityImpl extends BasicStartableImpl implements TerraformEntity {

    private static final Logger LOG = LoggerFactory.getLogger(TerraformEntityImpl.class);

    public TerraformEntityImpl() {
    }

    @Override
    public void start(Collection<? extends Location> locations) {
        LOG.info("...starting terraform entity...");
        final String configurationUrl = this.getConfig(TerraformConfiguration.CONFIGURATION_URL);
        if (Strings.isBlank(configurationUrl)) {
            throw new IllegalStateException("Could not resolve Terraform configuration from " +
                    TerraformConfiguration.CONFIGURATION_URL.getName());
        }
        LOG.info(" Deploying Config from {}", configurationUrl); // we only support configuration via URL at the moment
        convertConfigToTerraformEnvVar(this);
        super.start(locations);
    }
}
