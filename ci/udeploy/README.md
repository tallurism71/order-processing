# uDeploy (IBM UrbanCode Deploy) setup

Used when the bank's release process requires uDeploy instead of Harness.

| uDeploy object | Value |
|---|---|
| Application | `order-processing` |
| Components | `order-service`, `inventory-service`, `payment-service`, `reconciliation-batch` |
| Component version | the immutable image tag built by Jenkins, e.g. `1.0.412-3f9c2ab1` |
| Environments | DEV → QA → UAT → PROD, with approval gates on UAT and PROD |
| Environment properties | ECR registry, cluster name, namespace, kustomize overlay |

## Component process (same for every service)

1. `aws eks update-kubeconfig --name ${p:environment/cluster}` (or `oc login` for OpenShift)
2. `kustomize edit set image ${p:component.name}=${p:environment/registry}/${p:component.name}:${p:version.name}`
3. `kubectl apply -k deploy/k8s/overlays/${p:environment/overlay}`
4. `kubectl rollout status deploy/${p:component.name} --timeout=300s`. If this fails, run `kubectl rollout undo` and fail the process.
5. Smoke test: `GET /actuator/health/readiness` through the environment's ingress.

## Application process "Deploy All Services"

Deploys the components in parallel. The database changes ship inside each service: Flyway runs on startup
and every migration is backward compatible (expand, then contract), so mixed versions can run side by side
during a rolling deploy.

Version statuses (`CI Passed`, `QA Passed`, `UAT Signed Off`) are added by each stage and checked by the
next environment's gates. That gives an audit trail of which build reached which environment, and who approved it.
