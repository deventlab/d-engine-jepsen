# docker/jepsen/Makefile
.PHONY: build test test-five-voters test-summary verdict run-workload test-scan-watch test-membership test-membership-readonly test-membership-single test-durability view report clean restart-stack ssh-setup proto-gen

# Configurable parameters
TIME_LIMIT       ?= 60
WORKLOAD         ?= register
FAULTS           ?= partition
RATE             ?= 10
NEMESIS_INTERVAL ?= 10
LAZYFS           ?=
NODE1            ?= node1
NODE2            ?= node2
NODE3            ?= node3
JEPSEN_CONTAINER ?= d-engine-jepsen-jepsen-1
ENDPOINTS        ?= http://node1:9081,http://node2:9082,http://node3:9083
COMPOSE_FILE     ?= ./docker-compose.yml

# Five-voter runs: FIVE=1 starts node1..node5 as voters from the start (see config/five/).
# Without FIVE every command line below is unchanged.
FIVE             ?=
ENDPOINTS_FIVE   ?= http://node1:9081,http://node2:9082,http://node3:9083,http://node4:9085,http://node5:9086
# register uses 3 threads per key, so the worker count must be a multiple of 3.
FIVE_ARGS        = $(if $(FIVE),--node node4 --node node5 --five-voters --concurrency 6,)
RUN_ENDPOINTS    = $(if $(FIVE),$(ENDPOINTS_FIVE),$(ENDPOINTS))

# Regenerate Java proto stubs from d-engine proto definitions.
# Requires protoc 3.25.3 matching protobuf-java 3.25.3 in project.clj.
# Download: https://repo1.maven.org/maven2/com/google/protobuf/protoc/3.25.3/protoc-3.25.3-osx-aarch_64.exe
# After downloading: chmod +x <binary> and set PROTOC to its path.
PROTOC ?= protoc-3.25.3
proto-gen:
	$(PROTOC) \
	  --proto_path=../d-engine/d-engine-proto \
	  --java_out=java-src \
	  proto/error.proto \
	  proto/common.proto \
	  proto/client/client_api.proto

# Commit the node images are built from. "-dirty" marks uncommitted changes. Jepsen records it
# with every result, so a result always says which code ran.
GIT_SHA ?= $(shell git -C ../d-engine describe --always --dirty 2>/dev/null || echo unknown)

# Build Docker images (auto-loads docker-compose.override.yml if present)
build:
	docker compose build --build-arg GIT_SHA=$(GIT_SHA)

# Restart Docker Compose stack
restart-stack:
	@echo "Cleaning output directories..."
	@rm -rf ./output/logs/* ./output/db/*
	@echo "Restarting Docker Compose stack..."
	@docker compose -f $(COMPOSE_FILE) down
	@docker compose -f $(COMPOSE_FILE) $(if $(LAZYFS),--profile lazyfs,) up -d
	@echo "Waiting for cluster to initialize (10 seconds)..."
	@sleep 10

# Print the verdict of the latest stored run: PASS, FAIL or INCONCLUSIVE
# (the checker could not decide, :valid? is :unknown).
verdict:
	@docker exec $(JEPSEN_CONTAINER) bash -c '\
		lein trampoline run -m clojure.main -e "\
			(require '\''[knossos.model :as model])\
			(println \
				(case (-> (clojure.edn/read-string \
					{:readers {\
						'\''knossos.model.Register model/->Register\
						'\''knossos.model.CASRegister model/->CASRegister\
						'\''knossos.model.Inconsistent model/->Inconsistent}\
					 :default (fn [_ v] v)}\
					(slurp \"/app/store/latest/results.edn\"))\
					:valid?)\
				true \"PASS\" false \"FAIL\" \"INCONCLUSIVE\"))"'

# Full suite: every scenario below runs, whatever the earlier ones did. Each ends as
#   PASS          the checker accepted the history
#   FAIL          the checker found a violation
#   INCONCLUSIVE  the checker could not decide
#   ERROR         the run left no new verdict (harness, docker or ssh failure)
# A table with one line per scenario is printed at the end and the exit status is
# non-zero unless every scenario passed. The table is kept in $(SUMMARY) and the
# output of each scenario in $(LOG_DIR)/NAME.log.
SUMMARY ?= ./output/test-summary.tsv
LOG_DIR ?= ./output/test-logs
STORE_LATEST = docker exec $(JEPSEN_CONTAINER) readlink /app/store/latest 2>/dev/null
# $(call) splits its arguments on commas, so a comma inside one is written $(comma).
comma := ,

# scenario NAME NODES TARGET [PARAMETERS]: runs one make target, appends one line to the summary.
# The verdict is only read when the run produced a new store entry, so a crashed run can not
# inherit the verdict of the previous scenario.
define scenario
@mkdir -p $(LOG_DIR); echo "=== test: $(1) ($(2) nodes) ==="; \
before=$$($(STORE_LATEST)); start=$$(date +%s); \
$(MAKE) --no-print-directory $(3) 2>&1 | tee $(LOG_DIR)/$(1).log; \
after=$$($(STORE_LATEST)); elapsed=$$(( $$(date +%s) - start )); \
if [ -n "$$after" ] && [ "$$after" != "$$before" ]; then verdict=$$($(MAKE) -s --no-print-directory verdict 2>/dev/null | tail -1); else verdict=ERROR; fi; \
case "$$verdict" in PASS|FAIL|INCONCLUSIVE) ;; *) verdict=ERROR;; esac; \
printf '%s\t%s\t%s\t%ss\n' "$(1)" "$(2)" "$$verdict" "$$elapsed" >> $(SUMMARY); \
echo "=== $(1): $$verdict ($${elapsed}s) ==="
endef

define three-voter-scenarios
$(call scenario,register-kill,3,run-workload WORKLOAD=register FAULTS=kill)
$(call scenario,bank,3,run-workload WORKLOAD=bank)
$(call scenario,set,3,run-workload WORKLOAD=set)
$(call scenario,append,3,run-workload WORKLOAD=append)
$(call scenario,watch,3,run-workload WORKLOAD=watch)
$(call scenario,scan-watch,3,test-scan-watch)
$(call scenario,membership-promotable,3,test-membership)
$(call scenario,membership-readonly,3,test-membership-readonly)
$(call scenario,membership-single-learner,3,test-membership-single TIME_LIMIT=420)
endef

# Five voters, from the start: the faults whose effect depends on the quorum size
# (a partition can leave the leader with one follower, a kill can take two nodes).
# leader-partition cuts the leader plus one follower away from the other three, and the
# run is only valid if that really happened. leader-pause freezes only the leader, so the
# others elect a new one. register-lease reads under the read lease: the reads that must
# not return a value the new leader has already overwritten.
define five-voter-scenarios
$(call scenario,register-partition-pause-5v,5,run-workload FIVE=1 WORKLOAD=register FAULTS=partition$(comma)pause)
$(call scenario,register-kill-5v,5,run-workload FIVE=1 WORKLOAD=register FAULTS=kill)
$(call scenario,bank-5v,5,run-workload FIVE=1 WORKLOAD=bank)
$(call scenario,append-5v,5,run-workload FIVE=1 WORKLOAD=append)
$(call scenario,register-leader-partition-5v,5,run-workload FIVE=1 WORKLOAD=register FAULTS=leader-partition)
$(call scenario,register-lease-leader-partition-5v,5,run-workload FIVE=1 WORKLOAD=register-lease FAULTS=leader-partition)
$(call scenario,register-lease-leader-pause-5v,5,run-workload FIVE=1 WORKLOAD=register-lease FAULTS=leader-pause)
endef

# Run all scenarios sequentially, three voters first, then five.
#   make test
#   make test FAULTS=kill,partition TIME_LIMIT=120
test:
	@rm -f $(SUMMARY)
	$(three-voter-scenarios)
	$(five-voter-scenarios)
	@$(MAKE) --no-print-directory test-summary

# Only the five-voter scenarios.
#   make test-five-voters
test-five-voters:
	@rm -f $(SUMMARY)
	$(five-voter-scenarios)
	@$(MAKE) --no-print-directory test-summary

# Print the summary table; fail unless every scenario passed.
test-summary:
	@echo ""; echo "=== Summary ($(SUMMARY)) ==="; \
	awk -F'\t' 'BEGIN { printf "%-34s %-6s %-13s %s\n", "SCENARIO", "NODES", "VERDICT", "TIME" } \
	  { printf "%-34s %-6s %-13s %s\n", $$1, $$2, $$3, $$4; if ($$3 != "PASS") bad++ } \
	  END { if (bad > 0) { printf "\n%d of %d scenarios did not pass\n", bad, NR; exit 1 } \
	        printf "\nAll %d scenarios passed\n", NR }' $(SUMMARY)

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
		    --node '"${NODE3}"' $(FIVE_ARGS) \
		    --endpoints '"${RUN_ENDPOINTS}"' \
		    --time-limit '"${TIME_LIMIT}"' \
		    --workload '"${WORKLOAD}"' \
		    --faults '"${FAULTS}"' \
		    --rate '"${RATE}"' \
		    --nemesis-interval '"${NEMESIS_INTERVAL}"' \
		    '"$(if $(LAZYFS),--lazyfs,)"''
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

# Durability campaign: append/Elle workload + lazyfs + leader-plus-one kill
# (kills the current leader + one other node each cycle, leaves a third node
# alive so the workload keeps producing observable history).
#
# This is NOT part of `make test` and is not a pass/fail CI gate: catching a
# quorum-before-durable-persist violation needs sustained high write pressure
# so an unflushed backlog exists when the kill lands, and a single run has
# no statistical weight either way. Treat it as a campaign — run it
# repeatedly / for longer before trusting any result. See
# 444-445-jepsen-lazyfs-methodology-gap.md for the full rationale.
#   make test-durability
#   make test-durability RATE=500 TIME_LIMIT=600
test-durability: LAZYFS := 1
test-durability: RATE := 200
test-durability: TIME_LIMIT := 300
test-durability: NEMESIS_INTERVAL := 5
test-durability: restart-stack
	@echo "Starting durability campaign: rate=$(RATE) time=$(TIME_LIMIT)s (leader+1 kill, lazyfs)"
	docker exec -e SSH_AUTH_SOCK=/ssh-agent $(JEPSEN_CONTAINER) bash -c '\
		  eval "$$(ssh-agent -s)" && \
		  ssh-add /root/.ssh/id_rsa && \
		  lein run test \
		    --node '"${NODE1}"' \
		    --node '"${NODE2}"' \
		    --node '"${NODE3}"' \
		    --endpoints '"${ENDPOINTS}"' \
		    --time-limit '"${TIME_LIMIT}"' \
		    --workload append \
		    --faults kill \
		    --rate '"${RATE}"' \
		    --nemesis-interval '"${NEMESIS_INTERVAL}"' \
		    --lazyfs'
	@echo "Durability campaign finished, checking result..."
	docker exec $(JEPSEN_CONTAINER) bash -c '\
		lein trampoline run -m clojure.main -e "\
			(require '\''[knossos.model :as model])\
			(println \
				(case (-> (clojure.edn/read-string \
					{:readers {\
						'\''knossos.model.Register model/->Register\
						'\''knossos.model.CASRegister model/->CASRegister\
						'\''knossos.model.Inconsistent model/->Inconsistent}\
					 :default (fn [_ v] v)}\
					(slurp \"/app/store/latest/results.edn\"))\
					:valid?)\
				true \"✅ PASS\"\
				false \"❌ FAIL\"\
				\"⚠️  INCONCLUSIVE (not a pass, check :anomaly-types)\"))"'

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
