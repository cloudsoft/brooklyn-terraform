package io.cloudsoft.terraform;

import org.apache.brooklyn.api.entity.Application;
import org.apache.brooklyn.camp.brooklyn.AbstractYamlTest;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.Dumper;
import org.apache.brooklyn.core.entity.EntityAsserts;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.util.text.Strings;
import org.apache.commons.lang3.tuple.Pair;
import org.testng.annotations.Test;

public class TerraformContainerDriverTest extends AbstractYamlTest {

    @Test(groups="Live") // requires access to a Kubernetes cluster via kubectl
    public void simpleEmptyApp() throws Exception {
        addCatalogItems(loadYaml("classpath://catalog.bom"));  // adding terraform type to the catalog

        Application app = createAndStartApplication(loadYaml("classpath://blueprints/no-location-sample-sns-inline.yaml")
        + Strings.lines("", "brooklyn.config:", "  tf_var.aws_access_key: "+getAwsKey().getLeft(), "  tf_var.aws_secret_key: "+getAwsKey().getRight())
        );

        Dumper.dumpInfo(app);
        EntityAsserts.assertAttributeEqualsEventually(app, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);
        EntityAsserts.assertAttributeEqualsEventually(app, Attributes.SERVICE_UP, true);
    }

    protected Pair<String,String> getAwsKey() {
        // TODO set this to read your keys e.g. from env var or a file ~/.brooklyn_test_aws_creds or similar
        throw new IllegalStateException();
        //return Pair.of("AWS_ACCESS_KEY", "AWS_SECRET_KEY");
    }

}
