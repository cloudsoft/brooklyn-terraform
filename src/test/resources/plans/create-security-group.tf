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

  tags = {
    Name = "TestSG-KillMePlease"
  }
}

output "name" {
    value = "${aws_security_group.allow_all.name}"
}

output "id" {
    value = "${aws_security_group.allow_all.id}"
}
