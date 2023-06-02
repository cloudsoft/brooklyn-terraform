# Configure the AWS Provider
provider "aws" {
  region     = var.region
  access_key = var.access_key
  secret_key = var.secret_key
}

resource "aws_key_pair" "ubuntu" {
  public_key = file("key.pub")

  tags = {
    Name = "test/tf-ubuntu-key"
  }
}
