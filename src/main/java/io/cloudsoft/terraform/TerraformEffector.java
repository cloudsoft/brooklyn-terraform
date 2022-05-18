package io.cloudsoft.terraform;


import org.apache.brooklyn.api.effector.Effector;
import org.apache.brooklyn.core.effector.AddEffectorInitializerAbstract;
import org.apache.brooklyn.core.effector.EffectorBody;
import org.apache.brooklyn.core.effector.Effectors;
import org.apache.brooklyn.util.core.config.ConfigBag;

import static com.google.common.base.Preconditions.checkNotNull;

@SuppressWarnings({"unchecked", "UnstableApiUsage", "unused"})
public class TerraformEffector extends AddEffectorInitializerAbstract {

    public TerraformEffector() {
    }

    @Override
    protected  Effectors.EffectorBuilder<String> newEffectorBuilder() {
        Effectors.EffectorBuilder<String> eff = newAbstractEffectorBuilder(String.class);
        eff.impl(new Body(eff.buildAbstract(), initParams()));
        return eff;
    }

    protected static class Body extends EffectorBody<String> {
        private final Effector<String> effector;
        private final ConfigBag params;

        public Body(Effector<String> eff, final ConfigBag params) {
            this.effector = eff;
            checkNotNull(params.getAllConfigRaw().get(TerraformCommons.COMMANDS.getName()), "command(s) must be supplied when defining this effector");
            checkNotNull(params.getAllConfigRaw().get(TerraformCommons.CONTAINER_IMAGE.getName()), "container image must be supplied when defining this effector");
            this.params = params;
        }

        @Override
        public String call(ConfigBag parameters) {
            ConfigBag configBag = ConfigBag.newInstanceCopying(this.params).putAll(parameters);
            // TODO
            return null;
        }
    }
}
