package io.cloudsoft.terraform;

import org.apache.brooklyn.api.entity.Application;
import org.apache.brooklyn.camp.brooklyn.AbstractYamlTest;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.Dumper;
import org.apache.brooklyn.core.entity.EntityAsserts;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.testng.annotations.Test;

public class TerraformContainerDriverTest extends AbstractYamlTest {

    @Test(groups="Live") // requires access to a Kubernetes cluster via kubectl
    public void simpleEmptyApp() throws Exception {
        addCatalogItems(loadYaml("classpath://catalog.bom"));  // adding terraform type to the catalog

        Application app = createAndStartApplication(loadYaml("classpath://blueprints/no-location-sample.yaml"));

        Dumper.dumpInfo(app);
        EntityAsserts.assertAttributeEqualsEventually(app, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);
        EntityAsserts.assertAttributeEqualsEventually(app, Attributes.SERVICE_UP, true);

    }

}
