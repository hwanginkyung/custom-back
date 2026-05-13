# Cariv Customs Terraform

이 폴더는 `cariv-customs` 백엔드 인프라(EC2 중심)를 Terraform으로 관리하기 위한 시작점입니다.

## FE(Vercel) 분리 여부

가능합니다. 프론트는 Vercel에서 별도 배포/관리하고,
이 Terraform은 백엔드 인프라만 관리해도 됩니다.

## 폴더 구조

- `envs/prod`: 운영 환경 루트
- `modules/customs-ec2`: customs 앱 서버(EC2) 모듈
- `bootstrap/state`: Terraform remote state용 S3 버킷(옵션: DynamoDB 잠금 테이블)

## 0) Remote State 부트스트랩(최초 1회)

1. `cd /Users/inkyung/carivAll/customs/cariv-customs/infra/bootstrap/state`
2. `terraform init`
3. `terraform apply`
4. 출력된 `state_bucket_name` 확인

현재 `envs/prod/versions.tf`는 아래 이름을 사용하도록 설정되어 있습니다.

- Bucket: `cariv-customs-prod-157128304289-tfstate-ap-northeast-2`
- Locking: `use_lockfile = true` (S3 native lockfile)

`DynamoDB` 락 테이블이 필요하면 `bootstrap/state`에서
`enable_dynamodb_lock_table = true`로 켠 뒤 apply하세요.

## 1) 처음 실행

1. `cd /Users/inkyung/carivAll/customs/cariv-customs/infra/envs/prod`
2. `cp terraform.tfvars.example terraform.tfvars`
3. `terraform.tfvars` 값 채우기
4. `terraform init -migrate-state`
5. `terraform plan`

## 운영 안전 기본값

- 모듈 리소스(`EC2/SG/EIP`)에 `prevent_destroy = true` 적용: 실수로 destroy 방지
- `envs/*/terraform.tfvars`는 Git 추적 제외 (`infra/.gitignore`)
- `terraform.tfstate`는 로컬 임시로만 사용하고, 운영은 S3 백엔드 전환 권장
- CloudWatch 알람 3종 기본 생성: `CPU high`, `StatusCheckFailed`, `NetworkOut high`

## 이미 있는 서버를 Terraform으로 가져오기(import)

이 프로젝트는 기본값이 `enabled = false`라서 실수로 생성되지 않습니다.

1. 기존 EC2 정보 조회

```bash
# 예시 인스턴스 ID: i-00b2fe73a3e162c33
INSTANCE_ID=i-00b2fe73a3e162c33
aws ec2 describe-instances --instance-ids "$INSTANCE_ID" \
  --query 'Reservations[0].Instances[0].{VpcId:VpcId,SubnetId:SubnetId,Ami:ImageId,Type:InstanceType,Key:KeyName,SgIds:SecurityGroups[*].GroupId,PublicIp:PublicIpAddress}' \
  --output table
```

2. `terraform.tfvars` 값 반영

- `enabled = true`
- `vpc_id`, `public_subnet_id`, `ami_id`, `instance_type`, `key_pair_name`
- 기존 SG 재사용 시 `manage_security_group = false` + `security_group_ids = ["sg-..."]`

3. 기존 리소스 import

```bash
terraform import 'module.customs_app.aws_instance.app[0]' i-xxxxxxxxxxxxxxxxx

# SG를 Terraform에서 직접 관리할 경우
terraform import 'module.customs_app.aws_security_group.app[0]' sg-xxxxxxxxxxxxxxxxx

# EIP를 쓰는 경우
terraform import 'module.customs_app.aws_eip.app[0]' eipalloc-xxxxxxxxxxxxxxxxx
```

4. `terraform plan`으로 diff 확인
5. 값(AMI, 타입, ingress CIDR 등)을 실제 리소스와 맞춰서 diff를 0에 가깝게 정리

## 주의

- `terraform apply`는 plan을 확인한 뒤 실행하세요.
- 현재 모듈은 `prevent_destroy=true` 이므로, 교체가 필요한 변경은 apply에서 막히는 것이 정상입니다.
- 시크릿은 절대 코드에 하드코딩하지 말고 환경변수/Secret Manager를 쓰세요.
