variable "key_name" {
  description = "Name of the SSH keypair to use in AWS."
  default = "cloudsoft"
}

variable "aws_region" {
  description = "AWS region to launch servers."
  default     = "us-east-1"
}

variable "aws_amis" {
  default = {
    "us-east-1" = "ami-c481fad3"
  }
}

# Specify the provider and access details
provider "aws" {
  region = "${var.aws_region}"
}

resource "aws_instance" "web" {
  instance_type = "t2.micro"

  # Lookup the correct AMI based on the region
  # we specified
  ami = "${lookup(var.aws_amis, var.aws_region)}"
  subnet_id = "subnet-46d07f0f"
  tags {
          Name = "tf"
  }
}

output "address" {
  value = "${aws_instance.web.private_ip}"
}
