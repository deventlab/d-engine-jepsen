# docker/jepsen/Makefile
.PHONY: test test-all view report clean restart-stack ssh-setup

# Configurable parameters
TIME_LIMIT       ?= 60
WORKLOAD         ?= register
FAULTS           ?= partition
RATE             ?= 10
NEMESIS_INTERVAL ?= 10
NODE1            ?= node1
NODE2            ?= node2
NODE3            ?= node3
JEPSEN_CONTAINER ?= d-engine-jepsen-jepsen-1
ENDPOINTS        ?= http://node1:9081,http://node2:9082,http://node3:9083
COMPOSE_FILE     ?= ./docker-compose.yml

# Restart Docker Compose stack
restart-stack:
	@echo "Cleaning output directories..."
	@rm -rf ./output/logs/* ./output/db/*
	@echo "Restarting Docker Compose stack..."
	@docker compose -f $(COMPOSE_FILE) down
	@docker compose -f $(COMPOSE_FILE) up -d
	@echo "Waiting for cluster to initialize (10 seconds)..."
	@sleep 10

# Run a single workload.
# Override any parameter on the command line, e.g.:
#   make test WORKLOAD=bank FAULTS=kill,partition RATE=20 TIME_LIMIT=120
test: restart-stack
	@echo "Starting Jepsen test: workload=$(WORKLOAD) faults=$(FAULTS) rate=$(RATE) time=$(TIME_LIMIT)s"
	docker exec -e SSH_AUTH_SOCK=/ssh-agent $(JEPSEN_CONTAINER) bash -c '\
		  eval "$$(ssh-agent -s)" && \
		  ssh-add /root/.ssh/id_rsa && \
		  lein run test \
		    --node '"${NODE1}"' \
		    --node '"${NODE2}"' \
		    --node '"${NODE3}"' \
		    --endpoints '"${ENDPOINTS}"' \
		    --time-limit '"${TIME_LIMIT}"' \
		    --workload '"${WORKLOAD}"' \
		    --faults '"${FAULTS}"' \
		    --rate '"${RATE}"' \
		    --nemesis-interval '"${NEMESIS_INTERVAL}"''
	@echo "Jepsen test finished, checking result..."
	docker exec $(JEPSEN_CONTAINER) bash -c '\
		lein trampoline run -m clojure.main -e "\
			(require '\''[knossos.model :as model])\
			(println \
				(if (-> (clojure.edn/read-string \
					{:readers {\
						'\''knossos.model.Register model/->Register\
						'\''knossos.model.CASRegister model/->CASRegister\
						'\''knossos.model.Inconsistent model/->Inconsistent}\
					 :default (fn [_ v] v)}\
					(slurp \"/app/store/latest/results.edn\"))\
				:valid?)\
				\"✅ PASS\" \"❌ FAIL\"))"'

# Run all workloads sequentially against a fresh cluster each time.
# Each workload exits non-zero on failure, stopping the suite early.
#   make test-all
#   make test-all FAULTS=kill,partition TIME_LIMIT=120
test-all:
	@echo "=== test-all: register ==="
	$(MAKE) test WORKLOAD=register
	@echo "=== test-all: bank ==="
	$(MAKE) test WORKLOAD=bank
	@echo "=== test-all: set ==="
	$(MAKE) test WORKLOAD=set
	@echo "=== test-all: append ==="
	$(MAKE) test WORKLOAD=append
	@echo "=== All workloads passed ✅ ==="

# High-concurrency stress test (RATE=200, 10 min).
# Surfaces low-probability linearizability violations that 60s/rate=10 cannot.
#   make test-stress
#   make test-stress WORKLOAD=bank
test-stress:
	$(MAKE) test WORKLOAD=$(WORKLOAD) FAULTS=kill,partition RATE=200 TIME_LIMIT=600

# Combined fault test (kill+partition, 5 min, moderate rate).
# Validates client failover under simultaneous process kill and network partition.
#   make test-combined
#   make test-combined WORKLOAD=bank
test-combined:
	$(MAKE) test WORKLOAD=$(WORKLOAD) FAULTS=kill,partition RATE=50 TIME_LIMIT=300

# Set up SSH agent inside container
ssh-setup:
	@echo "Configuring SSH keys..."
	@docker exec $(JEPSEN_CONTAINER) bash -c "\
		eval \$$(ssh-agent -s) && \
		ssh-add /root/.ssh/id_rsa"

# View latest test results
view:
	@latest=$$(readlink ./store/latest); \
	if [ -z "$$latest" ]; then \
		echo "No test results found"; \
		exit 1; \
	fi; \
	open "$$(pwd)/store/$$latest/index.html" 2>/dev/null || \
	echo "Open manually: file://$$(pwd)/$$latest/index.html"

# Show path to latest report
report:
	@latest=$$(readlink ./store/latest); \
	if [ -z "$$latest" ]; then \
		echo "No test results available"; \
		exit 1; \
	fi; \
	echo "Latest report: $$(pwd)/store/$$latest"

# Clean test artifacts
clean:
	rm -rf ./store/*
