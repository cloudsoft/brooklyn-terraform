---
title_in_menu: Terraform
title: Brooklyn Terraform Integration
layout: website-normal
---

This project provides an [Apache Brooklyn](https://brooklyn.apache.org/) entity for management of Terraform configuration.
[Terraform](https://terraform.io/) is a tool for building, changing, and versioning infrastructure safely and efficiently.


## Build

Clone the project then `cd` to the newly created repository and run:

```shell
mvn clean install
```

## Install

Use the [Brooklyn CLI](https://brooklyn.apache.org/download/index.html#command-line-client) to add the resulting bundle to the catalog(or import it from the GUI):

```shell
br catalog add target/brooklyn-terraform-1.1.0-SNAPSHOT.jar
```

Alternatively, for quick tests, copy the latest jar to the Brooklyn distribution's `dropins` directory
and then launch (or restart) Brooklyn.

```shell
    wget -O brooklyn-terraform-1.1.0-SNAPSHOT.jar "https://oss.sonatype.org/service/local/artifact/maven/redirect?r=snapshots&g=io.cloudsoft.terraform&a=brooklyn-terraform&v=1.1.0-SNAPSHOT&e=jar"
    mv brooklyn-terraform-1.1.0-SNAPSHOT.jar $BROOKLYN_HOME/lib/dropins/
    nohup $BROOKLYN_HOME/bin/brooklyn launch &
```

## Use

**Note** If you installed the project with `catalog.bom` then you can use the entity by using type
`terraform`. If you installed the dependencies manually then you should refer to the Java type
`io.cloudsoft.terraform.TerraformConfiguration` instead. Examples below refer to `terraform`.

The entity requires a value for one of the `tf.configuration.contents` and `tf.configuration.url`
cofiguration keys.

`tf.configuration.contents` allows you to include a plan directly in a blueprint.

`tf.configuration.url` has the entity load a remote resource at runtime. The resource must be accessible to the Brooklyn
server. The resource can be a single `configuration.tf` file or a `*.zip` archive containing multiple `*.tf` files and `terraform.tfvars` file.

When started the entity installs Terraform and applies the configured plan. For example, the following blueprint can be used to run
Terraform on localhost with a plan that provisions an instance in Amazon EC2 us-east-1 and assigns
it an elastic IP:

```yaml
location: localhost
name: Brooklyn Terraform Deployment
services:
- type: terraform
  name: Terraform Configuration
  brooklyn.config:
    tf.configuration.contents: |
        provider "aws" {
            access_key = "YOUR_ACCESS_KEY"
            secret_key = "YOUR_SECRET_KEY"
            region = "us-east-1"
        }

        resource "aws_instance" "example" {
            ami = "ami-408c7f28"
            instance_type = "t1.micro"
            tags {
                Name = "brooklyn-terraform-test"
            }
        }

        resource "aws_eip" "ip" {
            instance = "${aws_instance.example.id}"
        }
```

Instructions for declaring localhost as a location are given in the Brooklyn documentation for
[configuring localhost as a location](https://brooklyn.apache.org/v/latest/locations/index.html#localhost). 

**Note:** If you want to use a remote location, just make sure it is a Linux or Unix based, because currently the Brooklyn Terraform Drive does not work on Windows systems. 

### Terraform Outputs

The Terraform plan's [outputs](https://www.terraform.io/intro/getting-started/outputs.html) are published as Brooklyn sensors prefixed with `tf.output.`. Use this to communicate
information about the infrastructure created by Terraform to other components of the blueprint via Brooklyn's [dependent configuration](https://brooklyn.apache.org/v/0.11.0/yaml/yaml-reference.html#dsl-commands).

For example, to attach a `TomcatServer` to an AWS security group that was created by a Terraform plan:

```shell

services:

- type: org.apache.brooklyn.entity.webapp.tomcat.TomcatServer
  location:
    jclouds:aws-ec2:us-west-2:
      osFamily: centos
      templateOptions:
        securityGroupIds:
        - $brooklyn:component("tf").attributeWhenReady("tf.output.securityGroupId")
  brooklyn.config:
    launch.latch: $brooklyn:component("tf").attributeWhenReady("service.isUp")
    wars.root: http://search.maven.org/remotecontent?filepath=org/apache/brooklyn/example/brooklyn-example-hello-world-webapp/0.9.0/brooklyn-example-hello-world-webapp-0.9.0.war

- type: terraform
  id: tf
  location: localhost
  brooklyn.config:
    tf.configuration.contents: |
        # Credentials are given here for a self-contained blueprint. In practice you
        # would inject the values with an external configuration provider.
        provider "aws" {
            access_key = "..."
            secret_key = "..."
            region = "us-west-2"
        }

        resource "aws_security_group" "allow_all" {
          description = "test-security-group allowing all access"

          ingress {
            from_port = 0
            to_port = 0
            protocol = "-1"
            cidr_blocks = ["0.0.0.0/0"]
          }

          egress {
            from_port = 0
            to_port = 0
            protocol = "-1"
            cidr_blocks = ["0.0.0.0/0"]
          }
        }

        output "securityGroupId" {
            value = "${aws_security_group.allow_all.id}"
        }
```

### Terraform Managed Resources

Each resource that Terraform manages corresponds to an entity represented in Apache Brooklyn as a child of the Terraform Configuration entity. **(TODO add more information here after the discovery work is done)**

### Terraform Variables Support

Values for Terraform variables referenced in the configuration can be provided by declaring environment variables in the blueprint using `shell.env`.
The Terraform environment variables should be named according to the specifications in the [official Terraform documentation](https://www.terraform.io/docs/language/values/variables.html#environment-variables).

For example, the following blueprint describes a Terraform deployment with the configuration provided as a single file hosted on an Artifactory server. The AWS credentials values
are provided by a Vault installation using Terraform environment variables.

```yaml
location: localhost
name: Brooklyn Terraform Deployment With Environment Variables
services:
  - type: terraform
    name: Terraform Configuration
    brooklyn.config:
      tf.configuration.url: https://search.maven.org/remotecontent?filepath=org/apache/brooklyn/instance-with-vars.tf

      shell.env:
        TF_VAR_aws_identity: $brooklyn:external("vault", "aws_identity")
        TF_VAR_aws_credential: $brooklyn:external("vault", "aws_credential")
```

Brooklyn also supports providing a `terraform.tfvars` a remote resource at runtime using `tf.tfvars.url`.

```yaml
location: localhost
name: Brooklyn Terraform Deployment With remote 'terraform.tfvars'
services:
- type: terraform
  name: Terraform Configuration
  brooklyn.config:
    tf.configuration.url: https://search.maven.org/remotecontent?filepath=org/apache/brooklyn/big-config.zip
    tf.tfvars.url: https://[secure-location]/vs-terraform.tfvars 
```

Keep credentials out of your blueprint by using Brooklyn's [external configuration providers](https://brooklyn.apache.org/v/latest/ops/externalized-configuration.html).
For example, rather than including the `provider` block in the example above, you might write:
```yaml
    type: terraform
    brooklyn.config:
      aws.identity: $brooklyn:external("terraform", "aws.identity")
      aws.credential: $brooklyn:external("terraform", "aws.credential")

      shell.env:
        AWS_ACCESS_KEY_ID: $brooklyn:config("aws.identity")
        AWS_SECRET_ACCESS_KEY: $brooklyn:config("aws.credential")
        AWS_DEFAULT_REGION: $brooklyn:config("aws.region")
```

And configure the `terraform` provider in `brooklyn.properties`:
```shell
    # Refer to the Brooklyn docs for information on other kind of suppliers.
    brooklyn.external.terraform=org.apache.brooklyn.core.config.external.InPlaceExternalConfigSupplier
    brooklyn.external.terraform.aws.identity=...
    brooklyn.external.terraform.aws.credential=...
```

### Updating an Existing Deployment

Apache Brooklyn facilitates modifying an existing Terraform deployment through effectors and mutable config keys. 

#### Using the `reinstallConfig` Effector

The Terraform Configuration entity provides an effector named `reinstallConfig`. Invoking this effector causes the Terraform configuration files to be moved to the `/tmp/backup` directory and a set of configuration files to be downloaded from the URL provided as a parameter and copied in the Terraform workspace.
If the `/tmp/backup` directory exists, it is deleted. The URL is expected to point to a `*.zip` archive containing the new configuration files.
If no URL is provided, the effector uses the URL provided as a value for the `tf.configuration.url` when the blueprint is deployed.

This effector is useful when the `tf.configuration.url` points to a dynamic URL, such as a GitHub release(e.g. https://github.com/<REPO>/<PROJECT>/releases/latest/download/tf-config.zip) because it allows updating the Terraform configuration from a remote dynamic source.

**Note** Invoking the `reinstallConfig` effector will not affect the `*.tfvars` file that is provided using the `tf.tfvars.url` configuration key.

#### Customizing Terraform Variables Values Using Brooklyn Configurations

Apache Brooklyn allows injection of values for Terraform Variables using `brooklyn.config` and modifying those values after a Terraform configuration has been applied. 

In the following blueprint, a Brooklyn parameter named `resourceName` is declared having a property `reconfigurable` set to `true`. This means the value of this parameter can be edited after an application is deployed. 
The `resourceName` parameter is configured to have the value `overriddenResourceName`  in the `brooklyn.config` section of the Terraform Configuration service.
The value of this parameter is injected into the `TF_VAR_resource_name` environment variable using Brooklyn DSL. Terraform takes this value and uses it for the `resource_name` variable in the configuration.
In this blueprint, it is used as a `Name` tag for the created `aws_instance`.

```yaml
name: Brooklyn Terraform Deployment
location: localhost
services:
  - type: terraform
    name: Terraform Configuration
    brooklyn.config:
      resourceName: overriddenResourceName
      tf.configuration.contents: |
        variable resource_name {
        }

        provider "aws" {
            ...
        }

        resource "aws_instance" "resource1" {
            ami = "ami-02df9ea15c1778c9c"
            instance_type = "t1.micro"
            tags = {
                Name = "${var.resource_name}"
            }
        }  
      shell.env:
        TF_VAR_resource_name: '$brooklyn:config("resourceName")'
    brooklyn.parameters:
      - name: resourceName
        type: string
        reconfigurable: true
        default: defaultResourceName
```

The `resourceName` parameter value can be easily modified via the App Inspector UI in the Terraform Configuration entity's Config Summary Table.(Its value can also be changed using the `br` CLI, or via the REST API)
Once the variable is modified, a notification of the success/failure of the operation is displayed.
If the new value was accepted, the `tf.plan` sensor displays `{tf.plan.status=DESYNCHRONIZED, <resource change details>}` and Apache Brooklyn and Brooklyn sets the application `ON_FIRE`.
The `tf.plan.status=DESYNCHRONIZED` means the plan that was executed (based on the most recent configuration, that includes the new variable value) no longer matches the infrastructure, so the plan and the infrastructure are not in sync.

The user needs to invoke the `apply` effector for the Terraform Configuration entity to apply the changes of the updated configuration.

In about 30 seconds, at the next Apache Brooklyn inspection, if the `apply` effector executed correctly, all entities are shown as `RUNNING` and the `tf.plan` sensor displays  `{tf.plan.message=No changes. Your infrastructure matches the configuration., tf.plan.status=SYNC}`.

More information about drift management is available in the relevant section below.

### Destroy Operations

A `destroy` effector is provided for each entity matching a Terraform managed resource. Under the bonnet this effector executes`terraform destroy -auto-approve -target=<resource address>`.
Although it can be invoked from AMP, this will leave your deployment in an unpredictable state, depending on the dependencies between the resources. According to the official documentation,
The `-target option` is not for routine use, and is provided only for exceptional situations such as recovering from errors or mistakes, or when Terraform specifically suggests to use it as part of an error message. Applied changes may be incomplete.
The recommended way to discard your resources safely is to update the Terraform configuration and invoke the `reinstallConfig`. 

Invoking the `destroy` effector of a Terraform Configuration entity destroys the resources, but keeps the configuration accessible via the stopped entity. 
Undoing the effect of a `destroy` effector invocation on the Terraform Configuration entity is possible by invoking `reinstallConfig` effector of the Terraform Configuration entity. This recreates the managed resources and the entities matching them.

### Terraform Drift Managing

One challenge when managing infrastructure as code is drift. Drift is the term for when the real-world state of your infrastructure differs from the state defined in your configuration.
Apache Brooklyn collaborates with Terraform to report the status of the  managed infrastructure accurately. Apache Brooklyn uses the `terraform plan` command JSON output 
to extract information relevant to the situation the deployment is in and how it got there. That information is analyzed and the conclusions are displayed by the `tf.plan` sensor. The `tf.plan` sensors contains key-value pairs, containing, the plan state, resources that were changed,
outputs that were changed and the type of change. 

Apache Brooklyn inspects the Terraform deployment every 30 seconds and updates the sensors and the Brooklyn managed entities.

**Note** In this section infrastructure is used to describe a collection of cloud resources managed by Terraform. 

#### All is Well With the World

When the infrastructure is in the configured state, the `tf.plan` sensor displays  `{tf.plan.message=No changes. Your infrastructure matches the configuration., tf.plan.status=SYNC}`.
The `tf.plan.status=SYNC` means the plan that was executed (based on the provided configuration) is in sync with the infrastructure, so the plan and the infrastructure are in sync.

#### Resource is Changed Outside Terraform

When a resource is changed outside Terraform (e.g. the tag of an AWS instance is changed) the `tf.plan` sensor displays `{tf.plan.status=DRIFT, <resource change details>}`. This is known as an `update drift`.
The `tf.plan.status=DRIFT` means the plan that was executed (based on the provided configuration) no longer matches the managed infrastructure. Based on the information provided by the `tf.plan` sensor the affected entities are shown as being `ON_FIRE`. 
The Terraform Configuration entity managing it is reported to be `ON_FIRE`, so is the application. The entities that are not affected by the drift are shown as `RUNNING`.
In this situation manual intervention is required, and there are two possible actions:

- Invoking the `apply` effector of the Terraform Configuration entity resets the resources to their initial configuration (e.g. the tag of an AWS instance is reverted to the value declared in the configuration)
- Manually edit the terraform configuration file(s) to include the infrastructure updates and then invoke the `apply` effector

In about 30 seconds, at the next Apache Brooklyn inspection, if the `apply` effector executed correctly, all entities are shown as `RUNNING` and the `tf.plan` sensor displays  `{tf.plan.message=No changes. Your infrastructure matches the configuration., tf.plan.status=SYNC}`.

#### Resource and Output Declaration is Added to the Configuration File(s)

When a new resource or output declaration is manually added to the configuration file the `tf.plan` sensor displays `{tf.plan.status=DESYNCHRONIZED, <configuration change details>}`.
The `tf.plan.status=DESYNCHRONIZED` means the plan that was executed (based on the most recent configuration) no longer matches the infrastructure, so the plan and the infrastructure are not in sync.
The Terraform Configuration entity managing it is reported to be `ON_FIRE`, so is the application. The entities that are not affected by the drift are shown as `RUNNING`.
In this situation manual intervention is required, and the only possible action is to invoke the `apply` effector of the Terraform Configuration entity. This triggers Terraform to execute the updated plan, create the new resources and outputs.

In about 30 seconds, at the next Apache Brooklyn inspection, if the `apply` effector executed correctly, new entities corresponding the newly created resources are added, all entities are shown as `RUNNING` and the `tf.plan` sensor displays  `{tf.plan.message=No changes. Your infrastructure matches the configuration., tf.plan.status=SYNC}`.

#### Resource and Output Declaration is Removed to the Configuration File(s)

This situation is 99% to the previous one, with the exception being that at the next Apache Brooklyn inspection, entities matching deleted resources are removed. 

#### Only Output Declarations are Added/Removed to/from the Configuration File(s)

This situation is quite special since output configuration changing is not affecting the infrastructure in any way so Terraform is not that sensitive about it.
However, Apache Brooklyn is a stricter about this and any output configuration changes cause the `tf.plan` sensor to display `{tf.plan.status=DESYNCHRONIZED, <output change details>}`.
In this case the `tf.plan.status=DESYNCHRONIZED` means the plan that was executed had different outputs than the ones currently in the configuration, so the plan and configuration are not in sync.
The Terraform Configuration entity managing it is reported to be `ON_FIRE`, so is the application. The rest of the entities are not affected in any way. 

In this situation manual intervention is required, and the only possible action is to invoke the `apply` effector of the Terraform Configuration entity. This triggers Terraform to execute the updated plan, create/remove the new  outputs.

In about 30 seconds, at the next Apache Brooklyn inspection, if the `apply` effector executed correctly, new `tf.output.*` sensors are created, the ones that no longer match a Terraform output declaration are removed, 
and the `tf.plan` sensor displays  `{tf.plan.message=No changes. Your infrastructure matches the configuration., tf.plan.status=SYNC}`.

#### Resource is Destroyed Outside Terraform

When a resource is destroyed outside Terraform (e.g. an AWS instance is terminated) the `tf.plan` sensor displays `{tf.plan.status=DRIFT, <resource change details>}`. This is known as an `delete drift`.
The `tf.plan.status=DRIFT` means the plan that was executed (based on the provided configuration) no longer matches the managed infrastructure.

Based on the information provided by the `tf.plan` sensor the affected entities are shown as being `ON_FIRE`.
The Terraform Configuration entity managing it is reported to be `ON_FIRE`, so is the application. The entities that are not affected by the drift are shown as `RUNNING`.
In this situation manual intervention is required, and there are two possible actions:

- Invoking the `apply` effector of the Terraform Configuration entity resets the resources to their initial configuration (e.g. the missing resource is re-created with the details from the configuration)
- Manually edit the terraform configuration file(s) to remove the configuration for the destroyed resource and then invoke the `apply` effector

In about 30 seconds, at the next Apache Brooklyn inspection, if the `apply` effector executed correctly, all entities are shown as `RUNNING` and the `tf.plan` sensor displays  `{tf.plan.message=No changes. Your infrastructure matches the configuration., tf.plan.status=SYNC}`.
If the choice was to re-create the destroyed resource, an entity matching the new resource appears under the  Terraform Configuration entity, otherwise the entity without a matching resource is removed. 

#### Resource State is Not as Expected

This is a special situation when a resource is changed outside terraform, but the characteristic that changed is not something that Terraform manages. For example, let's consider a Terraform configuration declaring an AWS instance to be created.
The plan is executed and the resource is created. What happens if the AWS instance is stopped?

This resource state change is reported as an `update drift` by Terraform.
Based on the information provided by the `tf.plan` sensor the affected entity are shown as being `ON_FIRE`. 
The `tf.plan` sensor displays:

```
{
  tf.plan.message=Drift Detected. Configuration and infrastructure do not match. Run apply to align infrastructure and configuration. Configurations made outside terraform will be lost if not added to the configuration.Plan: 0 to add, 0 to change, 0 to destroy., 
  tf.plan.status=DRIFT, 
  tf.resource.changes=[
    {
      resource.addr=aws_instance.example,
      resource.action=update
    }
  ]
}
```
The Terraform Configuration entity managing it is reported to be `ON_FIRE`, so is the application. The entities that are not affected by the drift are shown as `RUNNING`. 
The `tf.plan` contents are somewhat conflicting because although there are resource changes, its message says `Plan: 0 to add, 0 to change, 0 to destroy.`
This is because the resource is unreacheable, but none of its configurations as known by terraform are changed. 

In this situation there are two possible actions:

- Invoke the `apply` effector of the Terraform Configuration entity, this will apply the configuration, conclude there is nothing to apply because nothing has changed. The resource state will be refreshed, and the new instance state of 'stopped' will be recorded.
- Manually start the instance and then invoke the `apply` effector,  this will apply the configuration, conclude there is nothing to apply because nothing has changed. The resource state will be refreshed, and the new instance state of 'running' will be recorded.

In about 30 seconds, at the next Apache Brooklyn inspection, if the `apply` effector executed correctly, the `tf.plan` sensor displays  `{tf.plan.message=No changes. Your infrastructure matches the configuration., tf.plan.status=SYNC}`.
If the instance was not started manually, the matching entity is shown as stopped (grey bubble). If the instance was started the matching entity is shown as running(green bubble). 
The Terraform Configuration entity managing and unaffected entities are shown as `RUNNING`.

#### Editing the Configuration File(s) Goes Wrong

Manually editing the Terraform configuration file(s) is a risky business(we are only humans, after all) and in case there are errors Apache Brooklyn reflects this situation as well.
In case of duplicate resources, or syntax errors, the `tf.plan` sensor displays `{tf.plan.status=ERROR, <hints about what is wrong>}`. There is also a special Apache Brooklyn sensor named `service.problems` 
that is populated with the details of the error and a very helpful message: `{"TF-ERROR":"Something went wrong. Check your configuration.<hints about what is wrong>"}`. 
This sensor causes the Terraform Configuration entity and the application to be reported as being `ON_FIRE`, but the entities matching resouces are shown as `RUNNING` since they are not affected by the configuration errors.

The only action possible in this situation is to repair the broken configuration file(s).  In about 30 seconds, at the next Apache Brooklyn inspection, all will be well with the world again. 
If valid changes were added to the configuration, invoking the `apply` effector is required.
