(ns memuse.core
  (:import (java.lang.management ManagementFactory MemoryType))
  (:require [clojure.core.async :as async :refer [<! >! <!! timeout chan alt!! go go-loop  close!]]
            [clojure.core.matrix :as matrix]
            [clojure.core.matrix.linear :as linear]
            [uncomplicate.neanderthal
             [native :as nn]
             [core :as nc]
             [linalg :as nl]
             ]
            [incanter
             [core :as icore]
             [stats :as istat]
             [charts :as icharts]
             [io :as iio]
             ]

            ))



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

(def gulping (atom {}))
(defn- track! [name dn]
  (let [m2 (swap! gulping (fn [m] (update-in m [name] #(+ dn (if % % 0)))))]
    (get m2 name)))


(defn gulp
  "Return channel for task name that consume n bytes for up to t ms"
  [name n t & [debug]]
  (go 
    (let [r     (track! name 1)
          _     (when debug (println "gulp inc" name "->" r))
          large (byte-array n)
          _     (<! (timeout (* t (rand))))
          r     (track! name -1)
          _     (when debug (println "gulp dec" name "->" r))
          ]
          (count large)
          )))

(defn launch
  "Launch l batches of m gulps, each allocating n bytes for up to t milliseconds"
  [name l m n t & [debug]]
  (go-loop [i 0]
    (when (<= i l)
      (let 
          [_  (when debug (println "batch" i "for" name))
           cs (take m (repeatedly #(gulp name n t debug)))
           vs (<! (async/map vector cs))]
        (recur (inc i))))))

(defn launch-many [num-sources length-ms max-mem max-batch & [debug]]
  (let [argss (take num-sources
                (repeatedly #(let [t  (inc (rand-int length-ms))
                                   l  (-> (/ length-ms t) int inc)
                                   n  (inc (rand-int max-mem))
                                   m  (inc (rand-int max-batch))]
                               [l m n t debug])))
        cs (map-indexed
            (fn [i args] (apply launch i args)) argss)
        c  (async/map vector cs)]
    [c argss]))


(defn measure [n dt]
  (go-loop [i 0
            r []]
    (if (<= i n)
      (let [m [i @gulping (mem-used) (mem-used-bean) (mem-used-pools)]
            _ (println m)
            _ (<! (timeout dt))]
        (recur (inc i) (conj r m)))
      r)))

(defn m->a [m]
  (let [ks (-> m first second keys sort)
        gs (map second m)
        a  (vec  (map (fn [g] (vec (map #(get g %) ks))) gs))]
    a
    ))

#_(defn m->b [m]
  (let [ng (-> m first second keys count)]
    (map #(nth % ng) m))  )

(defn m->b [m] (map #(nth % 2) m))


;; icanter matrices are vectors of rows
(defn col [m i] (map #(nth m i)))

;; a = \sum_i^M (U_i \cdot b / w_i) V_i
;; s_j = \sum_i^M (V_{ji}/w_i)^2
;;     = 

(defn coeffs [{U :U ws :S V :V*} b]
  (let [Uis (matrix/columns U)
        Vis (matrix/columns V)
        M   (matrix/dimension-count U 0)
        Vjs (matrix/rows  (matrix/submatrix V 0 M 0 M))
        as (apply matrix/add
           (map (fn [Ui wi Vi]
                  (matrix/mul (matrix/div (matrix/inner-product Ui b) wi) Vi))
                Uis ws Vis))
        ss (map (fn [Vj]
                   (let [x (matrix/div Vj ws)]
                     (matrix/inner-product x x)))
                 Vjs)
        ]
    [as ss]
   ))

