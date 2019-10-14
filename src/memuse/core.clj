(ns memuse.core
  (:use (incanter core stats charts io))
  (:import (java.lang.management ManagementFactory MemoryType))
  (:require [ clojure.core.async :as async :refer [<! >! <!! timeout chan alt!! go go-loop  close!]]))


(defn mem-used []
  (let [rt (Runtime/getRuntime)]
    (- (. rt totalMemory) (. rt freeMemory))
    )
)

(def mem-bean (ManagementFactory/getMemoryMXBean))

(defn pools [] (filter #(and
                         (. % isValid)
                         (= (. % getType) MemoryType/HEAP))
                       (ManagementFactory/getMemoryPoolMXBeans)))

(defn mem-used-pools [] (reduce + (map  #(.. % getUsage getUsed) (pools))))


(defn mem-used-bean  []
  (. mem-bean gc)
  (-> mem-bean (. getHeapMemoryUsage) (. getUsed)))


(defn foo
  "I don't do a whole lot."
  [x]
  (println x "Hello, World!"))

(def gulping (atom 0))

(defn gulp
  "Return channel that consume n bytes for up to t ms"
  [n t & [debug]]
  (go 
    (let [r     (swap! gulping inc)
          _     (when debug (println "gulp inc ->" r))
          large (byte-array n)
          _     (<! (timeout (* t (rand))))
          r     (swap! gulping dec)
          _     (when debug (println "gulp dec ->" r))
          ]
          (count large)
          )))

(defn launch
  "Launch l batches of m gulps, each allocating n bytes for up to t milliseconds"
  [l m n t & [debug]]
  (go-loop [i 0]
    (when (<= i l)
      (let 
          [_  (when debug (println "batch" i))
           cs (take m (repeatedly #(gulp n t debug)))
           vs (<! (async/map vector cs))]
        (recur (inc i))))))

(defn measure [n dt]
  (go-loop [i 0
            r []]
    (if (<= i n)
      (let [m [i @gulping (mem-used) (mem-used-bean) (mem-used-pools)]
            _ (println m)
            _ (<! (timeout dt))]
        (recur (inc i) (conj r m)))
      r)))
