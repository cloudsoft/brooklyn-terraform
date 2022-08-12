package io.cloudsoft.terraform;

import net.schmizz.sshj.common.SecurityUtils;
import org.apache.brooklyn.api.entity.Application;
import org.apache.brooklyn.api.location.LocationSpec;
import org.apache.brooklyn.camp.brooklyn.AbstractYamlTest;
import org.apache.brooklyn.camp.brooklyn.spi.dsl.DslUtils;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.Dumper;
import org.apache.brooklyn.core.entity.EntityAsserts;
import org.apache.brooklyn.core.entity.StartableApplication;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.location.jclouds.BlobStoreContextFactoryImpl;
import org.apache.brooklyn.location.jclouds.JcloudsLocation;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.stream.Streams;
import org.apache.brooklyn.util.text.Identifiers;
import org.apache.brooklyn.util.text.Strings;
import org.apache.commons.lang3.tuple.Pair;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.PageSet;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.options.ListContainerOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

public class TerraformContainerDriverTest extends AbstractYamlTest {

    private static final Logger LOG = LoggerFactory.getLogger(TerraformContainerDriverTest.class);

    String S3_BUCKET_NAME = "terraform-backend-test-cloudsoft1";

    @Override
    protected boolean useDefaultProperties() {
        return true;
    }

    @Test(groups="Live") // requires access to a Kubernetes cluster via kubectl
    public void simpleEmptyApp() throws Exception {
        doTest(null, true);
    }

    protected BlobStore getS3Access() {
        JcloudsLocation aws = mgmt().getLocationManager().createLocation(LocationSpec.create(JcloudsLocation.class)
                .configure(JcloudsLocation.CLOUD_PROVIDER, "aws-s3")
                .configure(JcloudsLocation.CLOUD_REGION_ID, "eu-west-1")
                .configure(JcloudsLocation.ACCESS_IDENTITY.getName(), DslUtils.parseBrooklynDsl(mgmt(), getAwsKey().getLeft()))
                .configure(JcloudsLocation.ACCESS_CREDENTIAL.getName(), DslUtils.parseBrooklynDsl(mgmt(), getAwsKey().getRight())));
        return BlobStoreContextFactoryImpl.INSTANCE.newBlobStoreContext(aws).getBlobStore();
    }

    static {
        // this gives better error messages from sshj if it can't find bouncy castle;
        // this can happen in osgi fairly easily, and when it does it can be obscure to debug,
        // because it looks like ssh is just failing
        SecurityUtils.setRegisterBouncyCastle(true);
    }

    @Test(groups="Live") // requires access to a Kubernetes cluster via kubectl
    public void simpleBackendTest() throws Exception {
        BlobStore blobstore = getS3Access();
        if (!blobstore.containerExists(S3_BUCKET_NAME)) {
            throw new IllegalStateException("This test requires a container to exist in S3: "+S3_BUCKET_NAME);
        }

        String salt = Strings.makeRandomId(8);
        String key = "brooklyn-terraform-test/" + salt;
        LOG.info("Running s3 backend test with salt "+salt);
        Application app = doTest(Strings.lines(
                "  shell.env:",
                "    AWS_ACCESS_KEY_ID: "+getAwsKey().getLeft(),
                "    AWS_SECRET_ACCESS_KEY: "+getAwsKey().getRight(),
                "  tf.extra.templates.contents:",
                "    backend.tf: |",
                "      terraform {",
                "        backend \"s3\" {",
                "          bucket = \"" + S3_BUCKET_NAME + "\"",
                "          key    = \"" + key + "\"",
                "          region = \"eu-west-1\"",
                "        }",
                "      }",
                ""
        ), true);
        Blob blob = blobstore.getBlob(S3_BUCKET_NAME, key);
        Asserts.assertNotNull(blob);
        String data = Streams.readFullyString(blob.getPayload().openStream());
        Asserts.assertStringContains(data, "my_first_sns_topic");
        ((StartableApplication)app).stop();
        blob = blobstore.getBlob(S3_BUCKET_NAME, key);
        if (blob!=null) {
            data = Streams.readFullyString(blob.getPayload().openStream());
            Asserts.assertStringDoesNotContain(data, "my_first_sns_topic");
        }
        blobstore.removeBlob(S3_BUCKET_NAME, key);
    }

    @Test(groups="Live") // requires access to a Kubernetes cluster via kubectl
    public void templatedBackendTest() throws Exception {
        BlobStore blobstore = getS3Access();
        if (!blobstore.containerExists(S3_BUCKET_NAME)) {
            throw new IllegalStateException("This test requires a container to exist in S3: "+S3_BUCKET_NAME);
        }

        String salt = Strings.makeRandomId(8);
        String key = "brooklyn-terraform-test/" + salt;
        LOG.info("Running s3 backend test with salt "+salt);
        Application app = doTest(Strings.lines(
                "  shell.env:",
                "    AWS_ACCESS_KEY_ID: "+getAwsKey().getLeft(),
                "    AWS_SECRET_ACCESS_KEY: "+getAwsKey().getRight(),
                "  bucket_path: "+key,
                "  tf.extra.templates.contents:",
                "    backend.tf: |",
                "      terraform {",
                "        backend \"s3\" {",
                "          bucket = \"" + S3_BUCKET_NAME + "\"",
                "          key    = \"${config.bucket_path}\"",
                "          region = \"eu-west-1\"",
                "        }",
                "      }",
                ""
        ), true);
        Blob blob = blobstore.getBlob(S3_BUCKET_NAME, key);
        Asserts.assertNotNull(blob);
        String data = Streams.readFullyString(blob.getPayload().openStream());
        Asserts.assertStringContainsIgnoreCase(data, "my_first_sns_topic");
        ((StartableApplication)app).stop();
        blob = blobstore.getBlob(S3_BUCKET_NAME, key);
        if (blob!=null) {
            data = Streams.readFullyString(blob.getPayload().openStream());
            Asserts.assertStringDoesNotContain(data, "my_first_sns_topic");
        }
        blobstore.removeBlob(S3_BUCKET_NAME, key);
    }

    public Application doTest(String lines, boolean start) throws Exception {
        addCatalogItems(loadYaml("classpath://catalog.bom"));  // adding terraform type to the catalog

        String blueprint = loadYaml("classpath://blueprints/no-location-sample-sns-inline.yaml")
                + Strings.lines("",
                "brooklyn.config:",
                "  tf_var.aws_access_key: " + getAwsKey().getLeft(),
                "  tf_var.aws_secret_key: " + getAwsKey().getRight(),
                "  tf_var.topic_name: brooklyn-terraform-topic-test-" + Identifiers.makeRandomId(4) + "-DELETE")
                + (lines == null ? "" : "\n" + lines);
        Application app = start ? createAndStartApplication(blueprint) : createApplicationUnstarted(blueprint);

        if (start) {
            Dumper.dumpInfo(app);
            EntityAsserts.assertAttributeEqualsEventually(app, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);
            EntityAsserts.assertAttributeEqualsEventually(app, Attributes.SERVICE_UP, true);
        }

        return app;
    }

    protected Pair<String,String> getAwsKey() {
        // set this to read your keys e.g. in brooklyn.properties (or take from env var or a file ~/.brooklyn_test_aws_creds or similar)
        /*

brooklyn.external.aws-credentials=org.apache.brooklyn.core.config.external.InPlaceExternalConfigSupplier
brooklyn.external.aws-credentials.access-key=AKIAXXXXXXXXXXXXX
brooklyn.external.aws-credentials.secret-key=s3cr3ts3cr3ts3cr3ts3cr3ts3cr3ts3cr3ts3cr3t

         */
        return Pair.of("$brooklyn:external(\"aws-credentials\", \"access-key\")", "$brooklyn:external(\"aws-credentials\", \"secret-key\")");
    }

}
