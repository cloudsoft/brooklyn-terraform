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

## Building and Running

There are several options available for building and running.

### Downloading a standalone distro release

Download a standalone distro release from the [releases page of this repo](https://github.com/mikezaccardo/brooklyn-terraform/releases).

Extract the tarball:

    tar xzf brooklyn-terraform-dist.tar.gz

Navigate to the extracted folder:

    cd brooklyn-terraform

To run Apache Brooklyn with Terraform support bundled:

    ./start.sh launch

### Building a standalone distro

To build an assembly, simply run:

    mvn clean install

This creates a tarball with a full standalone application which can be installed in any *nix machine at:
    target/brooklyn-terraform-dist.tar.gz

It also installs an unpacked version which you can run locally:

     cd target/brooklyn-terraform-dist/brooklyn-terraform

To run Apache Brooklyn with Terraform support bundled:

     ./start.sh launch

For more information see the README (or `./start.sh help`) in that directory.

### Adding to Brooklyn dropins

An alternative is to build a single jar and to add that to an existing Brooklyn install.

First install Brooklyn. There are instructions at https://brooklyn.incubator.apache.org/v/latest/start/index.html

Then simply run:

    mvn clean install

You can copy the jar to your Brooklyn dropins folder, and then launch Brooklyn:

    cp target/brooklyn-terraform-0.1-SNAPSHOT.jar $BROOKLYN_HOME/lib/dropins/
    nohup $BROOKLYN_HOME/bin/brooklyn launch &

----

Copyright 2014-2015 by Cloudsoft Corporation Limited

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