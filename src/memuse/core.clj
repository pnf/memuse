(ns memuse.core
  (:import (java.lang.management ManagementFactory MemoryType))
  (:require [clojure.core.async :as async :refer [<! >! <!! timeout chan alt!! go go-loop  close!]]
            [clojure.core.matrix :as matrix]
            [clojure.core.matrix.linear :as linear]
            [clojure.spec.alpha :as s]
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


(def running (atom {}))
(defn- track! [name dn]
  (let [m2 (swap! running (fn [m] (update-in m [name] #(+ dn (if % % 0)))))]
    (get m2 name)))

(defn make-dict [sub]
  (let [name (:name sub)]
    (reduce
     (fn [dict sub]
       (if (or (contains? dict (:name sub)) ;; Construct dict from first appearance
               (not (:t-max sub))           ;; of complete specification
               )
         dict 
         (merge dict (make-dict sub))))
     {(:name sub) sub}
     (:subs sub))))


(defn task
  "Return channel for task 'name' that consumes n bytes for up to t ms, additionally launching subs,
  which is a sequence of vectors [task-name num-batches batch-size t-max subs]"
  ([sub & [debug dict]]
   (let [dict (or dict (make-dict sub))
         sub  (if (:t-max sub) sub ;; looks fully-specified
                  (let
                      [sub2 (get dict (:name sub))]
                    (when debug (println "Extending" sub "from " sub2))
                    (merge sub2 sub)))  ;; flesh out from dictionary
         {:keys [name num-batches batch-size bytes t-max subs]
          :or {batch-size 1}} sub]
     (assert (and t-max (pos? t-max)) (str "Missing :t-max in " sub))
     (when debug (println "launching" sub))
     (if num-batches ;; Strip batch info, and launch batches of one-offs
       (let [t2 (dissoc sub :num-batches :batch-size)]
         (go-loop [i 0 acc []]
           (if (<= i num-batches)
             (let [_  (when debug (println "batch" i "for" name))
                   cs (doall (take batch-size (repeatedly #(task t2 debug dict))))
                   vs (<! (async/map vector cs))]
               (recur (inc i) (conj acc vs)))
             acc)))
       (go 
         (try (let [r     (track! name 1)
                    _     (when debug (println "task inc" name "->" r))
                    large (byte-array bytes) ;; allocate something big and hold onto it
                    cs    (doall (map (fn [s]
                                        (when debug (println "Launching subtask" (:name s) "from" name))
                                        (task s debug dict))
                                      subs))
                    _     (<! (timeout (* t-max (rand))))
                    vs    (if (seq cs) (<! (async/map vector cs)) [])
                    r     (track! name -1)
                    _     (when debug (println "task dec" name "->" r))
                    ]
                [(count large) vs])
              (catch Exception e (do (println "Lordy be!" e) "boffo"))  )  )))))

(def tasks1 {:name 1 :num-batches 1 :batch-size 2 :bytes 1024 :t-max 100 :debug true
             :subs [{:name 2 :num-batches 1 :batch-size 1 :bytes 1024 :t-max 100} {:name 2 :batch-size 2}]
             })


(def tasks2 {:name 1 :num-batches 3 :batch-size 2 :bytes 1024 :t-max 100
            :subs [{:name 2 :num-batches 2 :batch-size 2 :bytes 2000000 :t-max 1000}
                   {:name 3 :num-batches 1 :batch-size 3 :bytes 5000000 :t-max 2000
                    :subs [{:name 2 :num-batches 3 :batch-size 10}]
                    }]
            })

(defn measure [n dt task-chan]
  (go-loop [i 0
            r []]
    (let
        [tr (async/poll! task-chan)]
      (cond
        (and (<= i n) (not tr)) ;; no limit, no result
        (let [m [i @running (mem-used) (mem-used-bean) (mem-used-pools)]
              _ (println m)
              _ (<! (timeout dt))]
          (recur (inc i) (conj r m)))
        (not tr) ;; hit limit, no result
        [r task-chan]
        :else ;; ready!
        [r tr]))))

(defn m->a [m]
  (let [ks (->> m last second keys sort)
        gs (map second m)
        a  (vec
            (map (fn [g] (vec (conj  (map #(or (get g %) 0) ks) 1))) gs))]
    a
    ))

(defn m->b [m] (map #(nth % 3) m))

;; a = \sum_i^M (U_i \cdot b / w_i) V_i
;; s_j = \sum_i^M (V_{ji}/w_i)^2
;;     =
;; M   = U   S    V*
;; nxm  nxn diag mxm

(defn coeffs [m & [n]]
  (let [a   (m->a m)
        b   (m->b m)
        {U :U ws :S V :V*} (if (map? a) a (linear/svd a))
        M   (matrix/dimension-count ws 0)
        M   (if n (min M n) M)
        V   (matrix/transpose V)
        Uis (take M (matrix/columns U))
        Vis (take M (matrix/columns V))
        Vjs (take M (matrix/rows V))
        as (apply matrix/add
           (map (fn [Ui wi Vi]
                  (matrix/mul (matrix/div (matrix/inner-product Ui b) wi)
                              Vi))
                Uis ws Vis))
        ss (map (fn [Vj]
                   (let [x (matrix/div Vj ws)]
                     (matrix/inner-product x x)))
                 Vjs)
        ]
    [as ss ws V]
   ))

