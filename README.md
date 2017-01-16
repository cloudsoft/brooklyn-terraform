---
title_in_menu: Terraform
layout: Brooklyn Terraform Integration
---

Brooklyn Terraform Integration
=======

[Terraform](https://terraform.io/) Terraform is a tool for building, changing, and versioning infrastructure safely and efficiently.

This repository contains the Brooklyn Terraform entity for lifecycle management of a Terraform configuration.

## Sample Blueprint

Below is a sample YAML blueprint that will install the Terraform CLI on the local machine and apply a simple Terraform configuration
to provision a VM on AWS EC2 and assign it an elastic IP.

```yaml
location: localhost
name: Brooklyn Terraform Deployment
services:
- type: io.cloudsoft.terraform.TerraformConfiguration
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

## Tips

The Terraform plan's [outputs](https://www.terraform.io/intro/getting-started/outputs.html)
are published as Brooklyn sensors prefixed with `tf.output`. Use this to communicate
information about the infrastructure created by Terraform to other components of the
blueprint via Brooklyn's [dependent configuration](https://brooklyn.apache.org/v/0.9.0/yaml/yaml-reference.html#dsl-commands).

For example, to attach a `TomcatServer` to an AWS security group that was created by
a Terraform plan:

```yaml
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

- type: io.cloudsoft.terraform.TerraformConfiguration
  id: tf
  location: localhost
  brooklyn.config:
    tf.configuration.contents: |
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

Keep credentials out of your blueprint by using Brooklyn's
[external configuration providers](https://brooklyn.apache.org/v/latest/ops/externalized-configuration.html).
For example, rather than including the `provider` block in the example above,
you might write:

```yaml
  type: io.cloudsoft.terraform.TerraformConfiguration
  brooklyn.config:
    aws.identity: $brooklyn:external("terraform", "aws.identity")
    aws.credential: $brooklyn:external("terraform", "aws.credential")

    shell.env:
      AWS_ACCESS_KEY_ID: $brooklyn:config("aws.identity")
      AWS_SECRET_ACCESS_KEY: $brooklyn:config("aws.credential")
      AWS_DEFAULT_REGION: $brooklyn:config("aws.region")
```

And configure the `terraform` provider in `brooklyn.properties`:
```
# Refer to the Brooklyn docs for information on other kind of suppliers.
brooklyn.external.terraform=org.apache.brooklyn.core.config.external.InPlaceExternalConfigSupplier
brooklyn.external.terraform.aws.identity=...
brooklyn.external.terraform.aws.credential=...
```


## Building and Running

There are several options available for building and running.

First install Brooklyn. There are instructions at https://brooklyn.apache.org/v/latest/start/index.html

Then copy the latest jar available at `https://oss.sonatype.org/content/repositories/snapshots/io/cloudsoft/terraform/brooklyn-terraform/0.1-SNAPSHOT/` into `$BROOKLYN_HOME/lib/dropins/`

### Building the project

An alternative you can build the project by running:

    mvn clean install

You can copy the jar to your Brooklyn dropins folder, and then launch (or restart) Brooklyn:

    cp target/brooklyn-terraform-0.1-SNAPSHOT.jar $BROOKLYN_HOME/lib/dropins/
    nohup $BROOKLYN_HOME/bin/brooklyn launch &

----

Copyright 2014-2016 by Cloudsoft Corporation Limited

> Licensed under the Apache License, Version 2.0 (the "License");
> you may not use this file except in compliance with the License.
> You may obtain a copy of the License at
>
> http://www.apache.org/licenses/LICENSE-2.0
>
> Unless required by applicable law or agreed to in writing, software
> distributed under the License is distributed on an "AS IS" BASIS,
> WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
> See the License for the specific language governing permissions and
> limitations under the License.
