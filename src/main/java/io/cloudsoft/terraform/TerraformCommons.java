package io.cloudsoft.terraform;

import com.google.common.collect.Sets;
import com.google.common.reflect.TypeToken;
import io.cloudsoft.terraform.util.Maps;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigConstraints;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.util.core.flags.SetFromFlag;
import org.apache.brooklyn.util.time.Duration;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Map;

public interface TerraformCommons {

    @SetFromFlag("tfPollingPeriod")
    ConfigKey<Duration> POLLING_PERIOD = ConfigKeys.builder(Duration.class)
            .name("tf.polling.period")
            .description("Contents of the configuration file that will be applied by Terraform.")
            .defaultValue(Duration.seconds(15))
            .constraint(input -> !input.isShorterThan(Duration.seconds(15))) // if shorter than 30s difficulties of executing 'apply' appear
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

    @SetFromFlag("tfVars")
    ConfigKey<String> TFVARS_FILE_URL = ConfigKeys.builder(String.class)
            .name("tf.tfvars.url") // should be part of deployed the bundle
            .description("URL of the file containing values for the Terraform variables.")
            .build();

    @SetFromFlag("tfExecutionMode")
    ConfigKey<String> TF_EXECUTION_MODE = ConfigKeys.builder(String.class)
            .name("tf.execution.mode") // should be part of deployed the bundle
            .description("If Terraform should run in a location ('ssh'), or in a container managed by a Kubernetes instance('kube').")
            .defaultValue("kube")
            .build();

    ConfigKey<Map<String,Object>> VOLUMES = ConfigKeys.builder(new TypeToken<Map<String,Object>>()  {}, "kubejob.config")
            .description("Configuration for the terraform job")
            .defaultValue(
                    Maps.newHashMap(
                            Pair.of("image", "cloudsoft/terraform:1.0"),
                            Pair.of("imagePullPolicy", "Never"),
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

    String SSH_MODE = "ssh";
    /**
     * I added this because commands can also be used by directly calling {@code docker run ...},
     * in case we even want to add a driver for that as well
     */
    String KUBE_MODE = "kube";

}
