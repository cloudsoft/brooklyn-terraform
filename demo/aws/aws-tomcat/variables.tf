variable "region" {
  # London
  default = "eu-west-2"
}

variable "access_key" {
}

variable "secret_key" {
}

variable "ami_id" {
  default = "ami-0ff4c8fb495a5a50d"
}

variable "ec2_type" {
  default = "t2.micro"
}

variable "tomcat_vm_name" {
  default = "Tomcat-VM"
}

variable "db_vm_name" {
  default = "MySQL-VM"
}