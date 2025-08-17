# springbootgateway
# Read Me First
The following was discovered as part of building this project:

# NBC DevOps Gateway Service

## Overview
This project provides a **Gateway Service** for NBC DevOps transactions. It is containerized with Docker and deployed via Kubernetes (Minikube for local testing). The service allows seamless connection between the transaction microservice and external clients using REST APIs.

---

## Prerequisites  optional you can build it localy i used jdk 18
Before running the service, ensure you have the following installed on your machine:

- **Docker** (latest version)
- **Docker Compose**
- **Kubernetes (Minikube)**
- **kubectl CLI**
- **httpie** (or curl) for API testing
- **Java 23** (Temurin JDK)
- **Maven 3.9.9**

---

## Setup Instructions

### 1. Clone and Extract Project
```bash
git clone <your-repo-link>
cd nbc-devops-transaction
# OR if received as a zip
unzip nbc-devops-transaction.zip
cd nbc-devops-transaction
```

### 2. Build Docker Image
```bash
# From project root
sudo docker build -t nbc-devops-gateway .
```

### 3. Run MySQL Container
```bash
sudo docker run -d   --name nbc-devops-mysql   -e MYSQL_ROOT_PASSWORD=D3vT35T_20250815   -e MYSQL_DATABASE=devopsdb   -p 3306:3306   mysql:latest
```

### 4. Run Service in Docker
```bash
sudo docker run -d   --name nbc-devops-webservice-local   --network host   nbc-devops-gateway
```

### 5. Deploy to Minikube
```bash
# Start Minikube
minikube start

# Apply deployment
kubectl apply -f nbc-devops-webservice-full.yaml -n temenos-fund-transfer

# Check status
kubectl get pods -n temenos-fund-transfer
kubectl get svc -n temenos-fund-transfer
```

---

## Stopping Docker Services
```bash
# Stop docker service completely
sudo systemctl stop docker.service
sudo systemctl stop docker.socket
```

---

## Testing the Service

### Using httpie
```bash
http GET http://localhost:8080/api/health
```

### Using curl
```bash
curl http://localhost:8080/api/health
```

You should see a response like:
```json
{"status":"UP"}
```

---

## Rollback Plan
If deployment fails or the service misbehaves:

1. **Remove Docker containers:**
```bash
sudo docker rm -f nbc-devops-mysql nbc-devops-webservice-local
```

2. **Prune networks and volumes:**
```bash
sudo docker system prune -f
sudo docker volume prune -f
```

3. **Delete Kubernetes resources:**
```bash
kubectl delete -f nbc-devops-webservice-full.yaml -n temenos-fund-transfer
```

4. **Restart services:** Redeploy Docker or Minikube as described above.

---

## Notes
- Database credentials are set as:
  - User: `root`
  - Password: `D3vT35T_20250815`
  - DB: `devopsdb`
- Gateway service runs on **port 8080**
- Ensure Minikube ingress and networking is enabled if accessing from outside local cluster.

