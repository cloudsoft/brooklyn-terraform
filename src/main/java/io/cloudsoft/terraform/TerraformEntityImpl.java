package io.cloudsoft.terraform;

import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.core.entity.AbstractEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;


public class TerraformEntityImpl extends AbstractEntity implements TerraformEntity {

    private static final Logger LOG = LoggerFactory.getLogger(TerraformEntityImpl.class);

    public TerraformEntityImpl() {
    }

    // TODO maybe add init() which sets the configuration


    @Override
    public void start(Collection<? extends Location> locations) {
        LOG.info("...starting terraform entity...");
    }

    @Override
    public void stop() {
        LOG.info("...stopping terraform entity...");
    }

    @Override
    public void restart() {
        LOG.info("...restarting terraform entity...");
    }
}
