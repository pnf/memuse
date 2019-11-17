(defproject memuse "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [incanter "1.9.3"]
                 [net.mikera/core.matrix "0.62.0"]
                 ;; [uncomplicate/neanderthal "0.26.1"]
                 [org.clojure/core.async "0.4.500"]]
  :repl-options {:init-ns memuse.core}
  ;; Oracle 12
  ;; :java-cmd "/Users/pnf/dist/jdk-12.0.2.jdk/Contents/Home/bin/java"
  ;; -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC
  ;; ["-XX:+UseG1GC" "-XX:MaxGCPauseMillis=200"]
  ;; :jvm-opts ["-XX:+UnlockExperimentalVMOptions" "-XX:+UseShenandoahGC"]
  ;; J9
  ;; :java-cmd "/Users/pnf/dist/jdk-12.0.2+10/Contents/Home/bin/java"
  ;; :java-cmd "/home/pnf/dist/jdk-12.0.2+10/bin/java"
  :java-cmd "/home/pnf/dist/graalvm-ce-19.2.1/bin/java"
  ;; :jvm-opts ["-Xgc:concurrentScavenge" ]
  ;; "--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED"
  ;; :plugins [[lein-with-env-vars "0.2.0"]]
  ;; :hooks [leiningen.with-env-vars/auto-inject]  ;; works for now
  ;; :env-vars {:LD_LIBRARY_PATH "/opt/intel/compilers_and_libraries/linux/mkl/lib/intel64:/opt/intel/lib"}

  )
