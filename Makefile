# d-engine-jepsen/Makefile
.PHONY: test view report clean download-binaries check-binaries

# Load environment from .env file if it exists
-include .env
export

# Configurable parameters
TIME_LIMIT ?= 60
NODE1 ?= node1
NODE2 ?= node2
NODE3 ?= node3
ENDPOINTS ?= http://node1:9081,http://node2:9082,http://node3:9083
COMPOSE_FILE ?= ./docker-compose.yml

# Binary URLs and paths
# S3_BASE_URL must be set in .env file (see .env.example)
ifndef S3_BASE_URL
$(error S3_BASE_URL is not defined. Please copy .env.example to .env and set S3_BASE_URL)
endif
BIN_DIR := ./bin
EMBEDDED_BIN := $(BIN_DIR)/three-nodes-embedded-linux-amd64
CTL_BIN := $(BIN_DIR)/dengine_ctl-linux-amd64
CHECKSUM_FILE := $(BIN_DIR)/checksums.sha256

# Download binaries from S3 if not present or checksum mismatch
download-binaries:
	@mkdir -p $(BIN_DIR)
	@echo "Downloading checksums from S3..."
	@curl -L -o $(CHECKSUM_FILE) $(S3_BASE_URL)/checksums.sha256
	@echo "Verifying binaries..."
	@if [ -f $(EMBEDDED_BIN) ] && [ -f $(CTL_BIN) ]; then \
		shasum -a 256 -c $(CHECKSUM_FILE) --status 2>/dev/null; \
		if [ $$? -eq 0 ]; then \
			echo "✓ All binaries verified successfully"; \
			exit 0; \
		else \
			echo "⚠ Checksum mismatch, re-downloading binaries..."; \
			rm -f $(EMBEDDED_BIN) $(CTL_BIN); \
		fi; \
	fi
	@if [ ! -f $(EMBEDDED_BIN) ]; then \
		echo "Downloading three-nodes-embedded-linux-amd64..."; \
		curl -L -o $(EMBEDDED_BIN) $(S3_BASE_URL)/three-nodes-embedded-linux-amd64; \
		chmod +x $(EMBEDDED_BIN); \
	fi
	@if [ ! -f $(CTL_BIN) ]; then \
		echo "Downloading dengine_ctl-linux-amd64..."; \
		curl -L -o $(CTL_BIN) $(S3_BASE_URL)/dengine_ctl-linux-amd64; \
		chmod +x $(CTL_BIN); \
	fi
	@echo "Verifying downloaded binaries..."
	@shasum -a 256 -c $(CHECKSUM_FILE) --quiet
	@echo "✓ Binary verification complete"

# Force re-download binaries
force-download:
	@rm -f $(EMBEDDED_BIN) $(CTL_BIN)
	@$(MAKE) download-binaries

# Check if Docker images exist, build if missing
check-images:
	@echo "Checking Docker images..."
	@if ! docker image inspect jepsen:2.0 > /dev/null 2>&1; then \
		echo "Building missing Docker image: jepsen:2.0"; \
		docker compose build jepsen; \
	else \
		echo "✓ jepsen:2.0 already exists"; \
	fi
	@if ! docker image inspect jepsen-node:2.0 > /dev/null 2>&1; then \
		echo "Building missing Docker image: jepsen-node:2.0"; \
		docker compose build node1; \
	else \
		echo "✓ jepsen-node:2.0 already exists"; \
	fi

# Restart Docker Compose stack
restart-stack: check-images download-binaries
	@echo "Cleaning output directories..."
	@rm -rf ../output/logs/* ../output/db/*
	@echo "Restarting Docker Compose stack..."
	@docker compose -f $(COMPOSE_FILE) down
	@docker compose -f $(COMPOSE_FILE) up -d
	@echo "Waiting for cluster to initialize (10 seconds)..."
	@sleep 10


# Main test target
test: restart-stack
	@echo "Starting Jepsen test with time limit: ${TIME_LIMIT}s"
	docker exec d-engine-jepsen-jepsen-1 lein run test \
		--node $(NODE1) \
		--node $(NODE2) \
		--node $(NODE3) \
		--ssh-private-key /root/.ssh/id_rsa \
		--endpoints $(ENDPOINTS) \
		--time-limit $(TIME_LIMIT)
	@echo "Jepsen test finished, checking result..."
	docker exec d-engine-jepsen-jepsen-1 bash -c '\
		lein trampoline run -m clojure.main -e "\
			(require '\''[knossos.model :as model])\
			(println \
				(if (-> (clojure.edn/read-string \
					{:readers {\
						'\''knossos.model.Register model/->Register\
						'\''knossos.model.CASRegister model/->CASRegister\
						'\''knossos.model.Inconsistent model/->Inconsistent}\
					}\
					(slurp \"/app/store/latest/results.edn\"))\
				:valid?)\
				\"✅ PASS\" \"❌ FAIL\"))"'

# Set up SSH agent inside container
ssh-setup:
	@echo "Configuring SSH keys..."
	@docker exec d-engine-jepsen-jepsen-1 bash -c "\
		eval \$$(ssh-agent -s) && \
		ssh-add /root/.ssh/id_rsa"

# View latest test results
view:
	@if [ ! -d ./store/latest ]; then \
		echo "No test results found"; \
		exit 1; \
	fi; \
	open "$$(pwd)/store/latest/independent/0/linear/linear.html" 2>/dev/null || \
	echo "Open manually: file://$$(pwd)/store/latest/independent/0/linear/linear.html"

# Show path to latest report
report:
	@if [ ! -d ./store/latest ]; then \
		echo "No test results available"; \
		exit 1; \
	fi; \
	echo "Latest report: $$(pwd)/store/latest"

# Clean test artifacts
clean:
	rm -rf ./store/*
