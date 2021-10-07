variable "key_name" {
  description = "Name of the SSH keypair to use in AWS."
  default = "cloudsoft"
}

variable "aws_region" {
  description = "AWS region to launch servers."
  default = "eu-west-1"
}

variable "aws_amis" {
  type = map(string)
  default = {
    "eu-west-1" = "ami-02df9ea15c1778c9c"
  }
}

# Specify the provider and access details
provider "aws" {
  region = "${var.aws_region}"
}

resource "aws_instance" "web" {
  instance_type = "t2.micro"

  tags = {
    Name = "TestInstance-KillMePlease"
  }

  # Lookup the correct AMI based on the region
  # we specified
  ami = "${lookup(var.aws_amis, var.aws_region)}"
}

output "address" {
  value = "${aws_instance.web.private_ip}"
}
