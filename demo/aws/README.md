# Demo - AWS with Terraform

There are two demos in this directory bases on a terraform template.
Both deploy a simple Tomcat website with a mysql database. The first
demo obtains the requred AWS credentials from a Terraform variables
file. The second obtains them through Brooklyn's External
Configuration.

The demos use Terraform, so you will need a Kubernetes environment
available.

```bash
minikube start
```

## Demo - Terraform Variables for authentication

Obtain your AWS Access Key and Secret Key. Copy the file
`terraform-sample.tfvars` as `terraform.tfvars`. Edit the new file and
add the AWS key in the first two lines, inside the double quotes.

To simlify making the variables file available to Brooklyn, start a
simple http server. e.g. Caddy.

```bash
caddy file-server --listen 0.0.0.0:9090
```

Verify that you can fetch the terraform.tfvars file through a browser
by visiting `http://YOUR-IP-ADDRESS:9090/terraform.tfvars`. This
should download the file.

Now open the Blueprint Composer and switch to the CAMP Editor tab.
Paste in the blueprint:

```yaml
__INCLUDE__ 01-aws-tomcat-demo.yaml
```

Change the url for `tf.tfvars.url` to
`http://YOUR-IP-ADDRESS:9090/terraform.tfvars`, as you used it above.
Remember that this needs to be your local IP address, that can been
accessed from the Brooklyn server if it isn't running on the same
machine as you.

Click the Deploy button on the top-right.

Navigate to the Inspector and verify that the application has
deployed.

You can stop `caddy` now, by pressing Ctrl-C.

You can browse to the deployed application's website by expanding the
`Apache Tomcat + MySQL on AWS Demo` application in the Inspector and
selecting the child `Terraform`. On the sensors tab, you can find the
URL for the new website under `tf.output.main_uri`. Click on the URL
to open the site in a new tab.

## Demo - External Configuration for authentication

In this demo, we will store the AWS Access Keys and Secret Key within
the Configuration of the Apache Brooklyn itself. For this you need to
edit the file `etc/brooklyn.cfg`.

Add the following lines to this file, replacing `XXX` with the AWS keys:

```
brooklyn.external.terraform=org.apache.brooklyn.core.config.external.InPlaceExternalConfigSupplier
brooklyn.external.terraform.aws.credential=XXX
brooklyn.external.terraform.aws.secret=XXX
```

Restart Apache Brooklyn to use this updated configuration file.

This create a new External Configuration called `terraform`, using the
`InPlaceExternalConfigSupplier`. There are other suppliers available,
this one uses the following lines to make the values for `aws.credential` and
`aws.secret` available. e.g. `$brooklyn:external("terraform",
"aws.credential")`

As with the preview demo, open the Blueprint Composer and switch to
the CAMP Editor tab. Paste in the blueprint:

```yaml
__INCLUDE__ 02-aws-tomcat-demo.yaml
```

You will see the references to `$brooklyn:external("terraform",
"aws.credential")` and `$brooklyn:external("terraform",
"aws.secret")` being used to access the AWS keys.

Click the Deploy button on the top-right.

Navigate to the Inspector and verify that the application has
deployed.

You can browse to the deployed application's website by expanding the
`Apache Tomcat + MySQL on AWS Demo` application in the Inspector and
selecting the child `Terraform`. On the sensors tab, you can find the
URL for the new website under `tf.output.main_uri`. Click on the URL
to open the site in a new tab.
