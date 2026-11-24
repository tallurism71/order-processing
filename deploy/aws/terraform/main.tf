# Low-cost demo: one EC2 instance runs the same docker-compose stack used locally
# (Postgres, Redis, Kafka, 3 services, Prometheus, Jaeger). The nightly reconciliation
# extract goes to a real, encrypted S3 bucket using the instance's IAM role.
# Production would use EKS + RDS + ElastiCache + MSK instead (see docs/ARCHITECTURE.md).

data "aws_caller_identity" "current" {}

data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
  filter {
    name   = "default-for-az"
    values = ["true"]
  }
}

# Always the latest Amazon Linux 2023 image, resolved from AWS's public SSM parameter.
data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

# ---------- S3 bucket for reconciliation extracts ----------

resource "aws_s3_bucket" "extracts" {
  bucket        = "order-processing-extracts-${data.aws_caller_identity.current.account_id}-${var.region}"
  force_destroy = true # demo only: lets `terraform destroy` remove the bucket with its files
}

resource "aws_s3_bucket_public_access_block" "extracts" {
  bucket                  = aws_s3_bucket.extracts.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "extracts" {
  bucket = aws_s3_bucket.extracts.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "extracts" {
  bucket = aws_s3_bucket.extracts.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "aws:kms" # AWS-managed key (aws/s3): no extra KMS key cost
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "extracts" {
  bucket = aws_s3_bucket.extracts.id
  rule {
    id     = "expire-old-extracts"
    status = "Enabled"
    filter {}
    expiration {
      days = var.extract_retention_days
    }
    noncurrent_version_expiration {
      noncurrent_days = 7
    }
  }
}

# ---------- IAM: the instance role (no access keys anywhere) ----------

resource "aws_iam_role" "app" {
  name_prefix = "order-processing-demo-"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

# Shell access through SSM Session Manager: no SSH key pair and no port 22 open.
resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.app.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# Least privilege: the batch job may only write extracts into its own bucket.
resource "aws_iam_role_policy" "extracts" {
  name = "write-extracts"
  role = aws_iam_role.app.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:GetObject"]
        Resource = "${aws_s3_bucket.extracts.arn}/*"
      },
      {
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = aws_s3_bucket.extracts.arn
      }
    ]
  })
}

resource "aws_iam_instance_profile" "app" {
  name_prefix = "order-processing-demo-"
  role        = aws_iam_role.app.name
}

# ---------- Network ----------

resource "aws_security_group" "app" {
  name_prefix = "order-processing-demo-"
  description = "Order API reachable only from allowed_cidr"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "Order API + Swagger UI"
    from_port   = 8081
    to_port     = 8081
    protocol    = "tcp"
    cidr_blocks = [var.allowed_cidr]
  }

  ingress {
    description = "Inventory API"
    from_port   = 8082
    to_port     = 8082
    protocol    = "tcp"
    cidr_blocks = [var.allowed_cidr]
  }

  egress {
    description = "Package installs, GitHub, Docker Hub, S3"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  lifecycle {
    create_before_destroy = true
  }
}

# ---------- The instance ----------

resource "aws_instance" "app" {
  ami                    = data.aws_ssm_parameter.al2023.value
  instance_type          = var.instance_type
  subnet_id              = data.aws_subnets.default.ids[0]
  vpc_security_group_ids = [aws_security_group.app.id]
  iam_instance_profile   = aws_iam_instance_profile.app.name

  user_data = templatefile("${path.module}/user-data.sh.tftpl", {
    repo_url       = var.repo_url
    git_ref        = var.git_ref
    extract_bucket = aws_s3_bucket.extracts.bucket
    region         = var.region
  })
  user_data_replace_on_change = true

  metadata_options {
    http_tokens                 = "required" # IMDSv2 only
    http_put_response_hop_limit = 2          # lets containers reach the instance role credentials
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = 30
    encrypted   = true
  }

  tags = {
    Name = "order-processing-demo"
  }

  lifecycle {
    ignore_changes = [ami] # don't replace the instance every time AWS publishes a new image
  }
}
