package io.cloudsoft.terraform;

import com.google.common.collect.Sets;
import com.google.common.reflect.TypeToken;
import io.cloudsoft.terraform.util.Constraints;
import io.cloudsoft.terraform.util.Maps;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigConstraints;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.config.MapConfigKey;
import org.apache.brooklyn.tasks.kubectl.ContainerCommons;
import org.apache.brooklyn.tasks.kubectl.PullPolicy;
import org.apache.brooklyn.util.core.flags.SetFromFlag;
import org.apache.brooklyn.util.core.predicates.DslPredicates;
import org.apache.brooklyn.util.time.Duration;
import org.apache.commons.lang3.tuple.Pair;

import java.io.InputStream;
import java.util.Map;
import java.util.function.Supplier;

public interface TerraformCommons {

    @SetFromFlag("tfPollingPeriod")
    ConfigKey<Duration> POLLING_PERIOD = ConfigKeys.builder(Duration.class)
            .name("tf.polling.period")
            .description("How often to refresh Terraform state. Default quite often, 30s.")
            .defaultValue(Duration.seconds(30))
            //.constraint(Constraints.lessThan(Duration.seconds(15))) // if shorter than 15s difficulties of executing 'apply' appear
            .build();

    @SetFromFlag("tfConfigurationContents")
    ConfigKey<String> CONFIGURATION_CONTENTS = ConfigKeys.builder(String.class)
            .name("tf.configuration.contents")
            .description("Contents of the configuration file that will be applied by Terraform.")
            .constraint(ConfigConstraints.forbiddenIf("tf.configuration.url"))
            .build();

    @SetFromFlag("tfDeployment")
    ConfigKey<String> CONFIGURATION_URL = ConfigKeys.builder(String.class)
            .name("tf.configuration.url")
            .description("URL of the configuration file that will be applied by Terraform.")
            .constraint(ConfigConstraints.forbiddenIf("tf.configuration.contents"))
            .build();

    ConfigKey<Supplier<InputStream>> CONFIGURATION_STREAM_SOURCE = ConfigKeys.builder(new TypeToken<Supplier<InputStream>>() {})
            .name("tf.configuration.stream_source")
            .description("Implementation that provides an input stream of the Terraform TF or ZIP to be used")
            .constraint(ConfigConstraints.forbiddenIf("tf.configuration.contents"))
            .constraint(ConfigConstraints.forbiddenIf("tf.configuration.url"))
            .build();

    @SetFromFlag("tfVars")
    ConfigKey<String> TFVARS_FILE_URL = ConfigKeys.builder(String.class)
            .name("tf.tfvars.url") // should be part of deployed the bundle
            .description("URL of the file containing values for the Terraform variables.")
            .build();

    @SetFromFlag("tfTemplatesContents")
    ConfigKey<Map<String,String>> EXTRA_TEMPLATES_CONTENTS = new MapConfigKey.Builder(String.class, "tf.extra.templates.contents")
            .description("Freemarker templates for files to be installed, keyed by path (in working dir, absolute, or relative to ~/), value being contents or templates")
            .build();

    ConfigKey<Boolean> TF_STATE_CONFLICTS_IN_ZIP_IGNORED = ConfigKeys.builder(Boolean.class)
            .name("tf.state_conflicts_in_zip_ignored")
            .description("Whether if the ZIP has *.tfstate files which conflict with those on disk, to ignore them. Should be set if injecting tfstate from a ZIP.")
            .build();

    @SetFromFlag("tfExecutionMode")
    ConfigKey<String> TF_EXECUTION_MODE = ConfigKeys.builder(String.class)
            .name("tf.execution.mode") // should be part of deployed the bundle
            .description("If Terraform should run in a location ('ssh'), or in a container managed by a Kubernetes instance ('kube'), or on the 'local' machine where AMP is running.")
            .defaultValue("kube")
            .build();

    ConfigKey<Map<String,Object>> KUBEJOB_CONFIG = ConfigKeys.builder(new TypeToken<Map<String,Object>>()  {}, "kubejob.config")
            .description("Configuration for the terraform job")
            .defaultValue(
                    Maps.newHashMap(
                            Pair.of("image", "cloudsoft/terraform:latest"),
//                            Pair.of("image", "hashicorp/terraform:latest"),   // this doesn't have unzip or even bash, so doesn't work with our bashScript approach
                            Pair.of("imagePullPolicy", PullPolicy.IF_NOT_PRESENT.val()),
                            Pair.of("workingDir", "/tfws"),
                            Pair.of("volumes", Sets.newHashSet(Maps.newHashMap(
                                    Pair.of("name", "terraform-workspace"),
                                    Pair.of("hostPath", Maps.newHashMap(Pair.of("path", "/tfws")))
                            ))),
                            Pair.of("volumeMounts", Sets.newHashSet(Maps.newHashMap(
                                    Pair.of("name", "terraform-workspace"),
                                    Pair.of("mountPath", "/tfws")
                            )))
                    )
            )
            .build();

    ConfigKey<String> CONTAINER_IMAGE = ContainerCommons.CONTAINER_IMAGE;

    ConfigKey<Duration> CONTAINER_TIMEOUT = ConfigKeys.newConfigKey(Duration.class, "container.timeout", "How long to wait for container-based Terraform commands (default 2 hours)", Duration.hours(2));

    String LOCAL_MODE = "local";
    String SSH_MODE = "ssh";
    /**
     * I added this because commands can also be used by directly calling {@code docker run ...},
     * in case we even want to add a driver for that as well
     */
    String KUBE_MODE = "kube";

}
