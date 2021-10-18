terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 3.27"
    }
  }
  backend "s3" {
    bucket = "terraform-bucket"
    key    = "socks-backend-demo"
    region = "us-east-1"
  }

  required_version = ">= 0.14.9"
}

provider "aws" {
  profile = "default"
  region  = "us-west-2"
}

resource "aws_db_instance" "db" {
  allocated_storage    = 1
  engine               = "mysql"
  engine_version       = "5.7"
  instance_class       = "db.t3.micro"
  name                 = "mydb"
  username             = "foo"
  password             = "foobarbaz"
  parameter_group_name = "default.mysql5.7"
  skip_final_snapshot  = true
}

resource "aws_instance" "tomcat-api-server" {
  ami = "ami-02df9ea15c1778c9c"
  instance_type = "t1.micro"
}

resource "aws_instance" "cache-server" {
  ami = "ami-02df9ea15c1778c9c"
  instance_type = "t1.micro"
}

variable "image_id" {
  type = string
  default = "hello world"
}

output "instance_ip_addr" {
  value = aws_instance.tomcat-api-server.public_ip
}
