# Kubernetes manifests

```
deploy/k8s/
├── base/               every service, defined once (Deployment, Service, HPA, PDB, CronJob, ConfigMap, ServiceAccount)
└── overlays/
    ├── local/          laptop cluster: adds Postgres, Redis and Kafka pods, local images, 1 replica
    ├── dev/            EKS dev: ECR images, 1 replica, full trace sampling
    └── prod/           EKS prod: ECR images, autoscaling up to 24 order-service pods
```

Kustomize overlays change only what differs per environment. Everything else comes from `base/`.

## Run it on your laptop (Docker Desktop)

1. **Turn on Kubernetes:** Docker Desktop → Settings → Kubernetes → *Enable Kubernetes* → *Apply & restart*.
   Wait for "Kubernetes running", then check:
   ```bash
   kubectl config use-context docker-desktop
   kubectl get nodes          # docker-desktop   Ready
   ```

2. **Build the images** (from the repository root):
   ```bash
   ./gradlew build -x test
   for m in order-service inventory-service payment-service reconciliation-batch; do
     docker build --build-arg MODULE=$m -t "${m}:local" .
   done
   ```

3. **Deploy:**
   ```bash
   kubectl apply -k deploy/k8s/overlays/local
   kubectl get pods -n order-processing-local -w      # Ctrl+C once all are Running and 1/1 Ready
   ```
   The services may restart once or twice while Postgres and Kafka are still starting.
   Kubernetes retries them on its own; that's self-healing at work.

4. **Use it:** Swagger UI at http://localhost:38081/swagger-ui.html

## Or run it with kind (no Docker Desktop setting needed)

[kind](https://kind.sigs.k8s.io) runs a Kubernetes cluster as a container in your existing Docker.

```bash
brew install kind
kind create cluster --name order-processing
# build the images as in step 2, then copy them into the cluster:
for m in order-service inventory-service payment-service reconciliation-batch; do
  kind load docker-image "${m}:local" --name order-processing
done
kubectl apply -k deploy/k8s/overlays/local
kubectl port-forward svc/order-service 38081:38081 -n order-processing-local   # kind has no LoadBalancer
```

Remove the whole cluster with `kind delete cluster --name order-processing`.

## Things to try

| Show | Command |
|---|---|
| Everything that's running | `kubectl get all -n order-processing-local` |
| Live logs for a service | `kubectl logs -f deploy/order-service -n order-processing-local` |
| **Self-healing**: delete a pod and watch a new one replace it | `kubectl delete pod -l app=payment-service -n order-processing-local` then `kubectl get pods -n order-processing-local -w` |
| **Scaling**: run 3 payment pods; Kafka splits the 6 partitions between them | `kubectl scale deploy/payment-service --replicas=3 -n order-processing-local` |
| **Rolling update** with no downtime | `kubectl rollout restart deploy/order-service -n order-processing-local` then `kubectl rollout status deploy/order-service -n order-processing-local` |
| Roll back | `kubectl rollout undo deploy/order-service -n order-processing-local` |
| **Run the nightly batch now** | `kubectl create job recon-now --from=cronjob/reconciliation-batch -n order-processing-local` then `kubectl logs -f job/recon-now -n order-processing-local` |
| Why is a pod unhealthy? | `kubectl describe pod <name> -n order-processing-local` |
| Readiness vs liveness | `kubectl get --raw /api/v1/namespaces/order-processing-local/services/order-service:http/proxy/actuator/health/readiness` |

A terminal dashboard makes all of this visual: `brew install k9s`, run `k9s`, then type `:pods`.

## Remove it

```bash
kubectl delete -k deploy/k8s/overlays/local
```

## How local differs from AWS

| | local overlay | dev / prod overlays |
|---|---|---|
| Postgres / Redis / Kafka | Pods in the cluster (data lost when a pod restarts) | RDS, ElastiCache, MSK |
| Images | Built on your Mac, `imagePullPolicy: Never` | ECR, immutable `version-gitsha` tags |
| Secrets | Demo passwords from `secretGenerator` | AWS Secrets Manager via External Secrets |
| Replicas | 1 (HPA up to 3) | 3+ across availability zones |
| Access | `LoadBalancer` on localhost:38081 | ALB Ingress (EKS) or Route (OpenShift) |
