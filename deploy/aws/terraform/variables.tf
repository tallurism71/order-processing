variable "region" {
  description = "AWS region to deploy into."
  type        = string
  default     = "us-east-1"
}

variable "instance_type" {
  description = "t3.large (8 GiB) runs everything comfortably. t3.medium (4 GiB) is cheaper but tight."
  type        = string
  default     = "t3.large"
}

variable "allowed_cidr" {
  description = "Only this CIDR can reach the API, e.g. your home IP as \"203.0.113.7/32\". Find it with: curl -s https://checkip.amazonaws.com"
  type        = string

  validation {
    condition     = can(cidrhost(var.allowed_cidr, 0)) && var.allowed_cidr != "0.0.0.0/0"
    error_message = "Use a specific CIDR such as your own IP with /32, not 0.0.0.0/0."
  }
}

variable "repo_url" {
  description = "Git repository the instance clones and builds."
  type        = string
  default     = "https://github.com/tallurism71/order-processing.git"
}

variable "git_ref" {
  description = "Branch or tag to deploy."
  type        = string
  default     = "main"
}

variable "extract_retention_days" {
  description = "Reconciliation extracts in S3 are deleted after this many days."
  type        = number
  default     = 30
}
