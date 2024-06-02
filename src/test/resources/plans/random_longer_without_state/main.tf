resource "random_string" "random" {
  length = var.length
  numeric = false
  upper = false
  special = false
}

variable "length" {
  default = 8
}

output "r" {
  value = random_string.random.id
}
