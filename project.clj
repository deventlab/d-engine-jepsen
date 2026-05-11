(defproject jepsen.dengine "0.2.4"
  :description "A Jepsen test for d-engine"
  :license {:name "MIT License"
            :url "https://opensource.org/licenses/MIT"}
  :main jepsen.d_engine
  :dependencies [[org.clojure/clojure "1.11.3"]
                 [jepsen "0.3.5"]
                 [knossos "0.3.8"]
                 [verschlimmbesserung "0.1.3"]
                 [net.java.dev.jna/jna "5.12.1"]
                 ;; gRPC native client
                 [io.grpc/grpc-netty-shaded "1.63.0"]
                 [io.grpc/grpc-protobuf "1.63.0"]
                 [io.grpc/grpc-stub "1.63.0"]
                 [com.google.protobuf/protobuf-java "3.25.3"]
                 [javax.annotation/javax.annotation-api "1.3.2"]]
  :java-source-paths ["java-src"]
  :jvm-opts ["-Djava.awt.headless=true"
             "-server"
             "-Xmx72g"])
