# docker/jepsen/Makefile
.PHONY: test run-workload test-scan-watch test-membership test-membership-readonly test-membership-single view report clean restart-stack ssh-setup

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

# Run all workloads sequentially — 100% coverage of all Jepsen guarantees.
# Each workload exits non-zero on failure, stopping the suite early.
#   make test
#   make test FAULTS=kill,partition TIME_LIMIT=120
test:
	@echo "=== test: register ==="
	$(MAKE) run-workload WORKLOAD=register FAULTS=kill
	@echo "=== test: bank ==="
	$(MAKE) run-workload WORKLOAD=bank
	@echo "=== test: set ==="
	$(MAKE) run-workload WORKLOAD=set
	@echo "=== test: append ==="
	$(MAKE) run-workload WORKLOAD=append
	@echo "=== test: watch ==="
	$(MAKE) run-workload WORKLOAD=watch
	@echo "=== test: scan-watch ==="
	$(MAKE) test-scan-watch
	@echo "=== test: membership (promotable) ==="
	$(MAKE) test-membership
	@echo "=== test: membership (readonly) ==="
	$(MAKE) test-membership-readonly
	@echo "=== test: membership (single-learner) ==="
	$(MAKE) test-membership-single TIME_LIMIT=420
	@echo "=== All workloads passed ✅ ==="

# Run a single workload (internal helper).
# Override any parameter on the command line, e.g.:
#   make run-workload WORKLOAD=bank FAULTS=kill,partition RATE=20 TIME_LIMIT=120
run-workload: restart-stack
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

# Scan-then-watch reconnection workload.
# Verifies no-gap, no-phantom, and revision-monotonicity under fault injection.
# Typical usage:
#   make test-scan-watch                              # partition faults, 60s
#   make test-scan-watch FAULTS=kill,partition TIME_LIMIT=300
#   make test-scan-watch RATE=200 FAULTS=none TIME_LIMIT=120  # trigger CANCELED via buffer overflow
test-scan-watch: restart-stack
	@echo "Starting scan-watch test: faults=$(FAULTS) rate=$(RATE) time=$(TIME_LIMIT)s"
	docker exec -e SSH_AUTH_SOCK=/ssh-agent $(JEPSEN_CONTAINER) bash -c '\
		  eval "$$(ssh-agent -s)" && \
		  ssh-add /root/.ssh/id_rsa && \
		  lein run test \
		    --node '"${NODE1}"' \
		    --node '"${NODE2}"' \
		    --node '"${NODE3}"' \
		    --endpoints '"${ENDPOINTS}"' \
		    --time-limit '"${TIME_LIMIT}"' \
		    --workload scan-watch \
		    --faults '"${FAULTS}"' \
		    --rate '"${RATE}"' \
		    --nemesis-interval '"${NEMESIS_INTERVAL}"''
	@echo "scan-watch test finished, checking result..."
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

# Membership workload: starts node4 and node5 as learners and verifies join/promote.
# Uses a 5-node cluster (node4/5 begin sshd-only and are started by the membership nemesis).
#   make test-membership
#   make test-membership FAULTS=kill,partition TIME_LIMIT=300
test-membership: restart-stack
	@echo "Starting membership test: faults=$(FAULTS) time=$(TIME_LIMIT)s"
	docker exec -e SSH_AUTH_SOCK=/ssh-agent $(JEPSEN_CONTAINER) bash -c '\
		  eval "$$(ssh-agent -s)" && \
		  ssh-add /root/.ssh/id_rsa && \
		  lein run test \
		    --node '"${NODE1}"' \
		    --node '"${NODE2}"' \
		    --node '"${NODE3}"' \
		    --node node4 \
		    --node node5 \
		    --endpoints '"${ENDPOINTS}"' \
		    --time-limit '"${TIME_LIMIT}"' \
		    --workload membership \
		    --faults '"${FAULTS}"' \
		    --rate '"${RATE}"' \
		    --nemesis-interval '"${NEMESIS_INTERVAL}"''
	@echo "Membership test finished, checking result..."
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

# ReadOnly membership: node4/5 join with status=ReadOnly, must never be promoted.
#   make test-membership-readonly
#   make test-membership-readonly FAULTS=kill,partition TIME_LIMIT=300
test-membership-readonly: restart-stack
	@echo "Starting readonly-membership test: faults=$(FAULTS) time=$(TIME_LIMIT)s"
	docker exec -e SSH_AUTH_SOCK=/ssh-agent $(JEPSEN_CONTAINER) bash -c '\
		  eval "$$(ssh-agent -s)" && \
		  ssh-add /root/.ssh/id_rsa && \
		  lein run test \
		    --node '"${NODE1}"' \
		    --node '"${NODE2}"' \
		    --node '"${NODE3}"' \
		    --node node4 \
		    --node node5 \
		    --endpoints '"${ENDPOINTS}"' \
		    --time-limit '"${TIME_LIMIT}"' \
		    --workload membership \
		    --membership-mode readonly \
		    --faults '"${FAULTS}"' \
		    --rate '"${RATE}"' \
		    --nemesis-interval '"${NEMESIS_INTERVAL}"''
	@echo "Readonly-membership test finished, checking result..."
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

# Single-learner membership: only node4 joins a 3-node cluster (3+1=4, even).
# node4 cannot be promoted; after 5-min stale_learner_threshold it is BatchRemoved.
# Runs with FAULTS=none by default: stale_learner_threshold is hardcoded at 300s and
# cannot be configured; frequent leader elections under partition faults extend the
# effective wait to 600-900s. Use FAULTS=none TIME_LIMIT=420 (default) for a clean
# deterministic test, or FAULTS=partition TIME_LIMIT=900 for fault-injection coverage.
#   make test-membership-single
#   make test-membership-single FAULTS=partition TIME_LIMIT=900
SINGLE_FAULTS ?= none
test-membership-single: restart-stack
	@echo "Starting single-learner membership test: faults=$(SINGLE_FAULTS) time=$(TIME_LIMIT)s"
	docker exec -e SSH_AUTH_SOCK=/ssh-agent $(JEPSEN_CONTAINER) bash -c '\
		  eval "$$(ssh-agent -s)" && \
		  ssh-add /root/.ssh/id_rsa && \
		  lein run test \
		    --node '"${NODE1}"' \
		    --node '"${NODE2}"' \
		    --node '"${NODE3}"' \
		    --node node4 \
		    --node node5 \
		    --endpoints '"${ENDPOINTS}"' \
		    --time-limit '"${TIME_LIMIT}"' \
		    --workload membership \
		    --membership-mode single-learner \
		    --faults '"${SINGLE_FAULTS}"' \
		    --rate '"${RATE}"' \
		    --nemesis-interval '"${NEMESIS_INTERVAL}"''
	@echo "Single-learner membership test finished, checking result..."
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

# High-concurrency stress test (RATE=200, 10 min).
# Surfaces low-probability linearizability violations that 60s/rate=10 cannot.
#   make test-stress
#   make test-stress WORKLOAD=bank
test-stress:
	$(MAKE) run-workload WORKLOAD=$(WORKLOAD) FAULTS=kill,partition RATE=200 TIME_LIMIT=600

# Combined fault test (kill+partition, 5 min, moderate rate).
# Validates client failover under simultaneous process kill and network partition.
#   make test-combined
#   make test-combined WORKLOAD=bank
test-combined:
	$(MAKE) run-workload WORKLOAD=$(WORKLOAD) FAULTS=kill,partition RATE=50 TIME_LIMIT=300

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
