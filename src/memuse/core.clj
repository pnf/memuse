(ns memuse.core
  (:import (java.lang.management ManagementFactory MemoryType))
  (:require [clojure.core.async :as async :refer [<! >! <!! timeout chan alt!! go go-loop  close!]]
            [clojure.core.matrix :as matrix]
            [clojure.core.matrix.linear :as linear]
            [clojure.spec.alpha :as s]
            [clojure.set :as set]
            ;;[uncomplicate.neanderthal [native :as nn] [core :as nc] [linalg :as nl]]
            [incanter
             [core :as icore]
             [stats :as istat]
             [charts :as icharts]
             [io :as iio]
             ]
            ))


;; Boring: get memory use straight from runtime
(defn mem-used []
  (let [rt (Runtime/getRuntime)]
       (- (. rt totalMemory) (. rt freeMemory))))


;; Heap usage from (single) MemoryMxBean
(def mem-bean (ManagementFactory/getMemoryMXBean))
(defn mem-used-bean  []
  (. mem-bean gc)
  (-> mem-bean (. getHeapMemoryUsage) (. getUsed)))

;; Sum memory usage across all heap pools
(defn- pools [] (filter #(and
                         (. % isValid)
                         (= (. % getType) MemoryType/HEAP))
                       (ManagementFactory/getMemoryPoolMXBeans)))
(defn mem-used-pools [] (reduce + (map  #(.. % getUsage getUsed) (pools))))

;; Keep track of population of each type of task currently running
(def running (atom {})) ;; name -> count
(defn- track! [name dn]
  (let [m2 (swap! running (fn [m] (update-in m [name] #(+ dn (if % % 0)))))]
    (get m2 name)))

(defn summary [sub]
  (into {} (map (fn [[name sub]]
                  [name [(::bytes sub) (map #(if (map? %) (::name %) %) (::subs sub))]])
                (make-dict sub))))

(s/def ::name (s/or :num int? :str string?))
(s/def ::num-batches int?)
(s/def ::batch-size int?)
(s/def ::bytes int?)
(s/def ::t-max int?)
(s/def ::subs (s/coll-of ::task-ref :kind vector?))
(s/def ::task (s/keys :req [::name ::t-max ::num-batches ::batch-size]
                      :opt [::debug ::subs]))
(s/def ::task-ref (s/or :ref (s/keys :req [::name]
                                     :opt [::num-batches ::batch-size ::debug])
                        :mini ::name
                        :def ::task))

;; Given a task launch specification, assemble dictionary of name -> task
(defn make-dict [task]
  (let [_    (assert (s/valid? ::task task) (s/explain ::task task))
        dict (letfn [(md [task]
                        (reduce (fn [dict task]
                                  (if-not (::t-max task) ;; complete specification
                                    dict
                                    (merge dict (make-dict task))))
                                {(::name task) task}
                                (::subs task)))]
                (md task))
        _    (letfn [(check [task]
                       (let [name (if (map? task) (::name task) task)]
                         (assert (contains? dict name) (str "Missing" name "in dict"))
                         (doseq [t (::subs task)] (check t))))])]
    dict))


;; Emergency brake
(def continue (atom true))

(defn launch
  "Return channel for task 'name' that consumes n bytes for up to t ms, additionally launching subs,
  which is a sequence of vectors [task-name num-batches batch-size
  t-max subs]"
  [task0 & [debug dict]]
  (when @continue
    (let [dict (or dict (make-dict task0))
          task task0
          tasks (cond
                (not (map? tasks)) (get dict tasks)
                (::t-max tasks)    tasks
                :else            (merge (get dict (::name tasks)) tasks))
          {:keys [::name ::num-batches ::batch-size ::bytes ::t-max ::subs]
           :or {batch-size 1}} tasks]
      (when debug (println "launching" tasks))
      (if num-batches ;; Strip batch info, and launch batches of one-offs
        (let [t2 (dissoc tasks ::num-batches ::batch-size)]
          (go-loop [i 0 acc []]
            (if (<= i num-batches)
              (let [_  (when debug (println "batch" i "for" name))
                    cs (doall (take batch-size (repeatedly #(launch t2 debug dict))))
                    vs (<! (async/map vector cs))]
                (recur (inc i) (conj acc vs)))
              acc)))
        (go 
          (try (let [r     (track! name 1)
                     _     (when debug (println "task inc" name "->" r))
                     large (byte-array bytes) ;; allocate something big and hold onto it
                     cs    (doall (map (fn [s]
                                         (when debug (println "Launching subtask" (::name s) "from" name))
                                         (launch s debug dict))
                                       subs))
                     _     (<! (timeout (* t-max (rand))))
                     vs    (if (seq cs) (<! (async/map vector cs)) [])
                     r     (track! name -1)
                     _     (when debug (println "task dec" name "->" r))
                     ]
                 [(count large) vs])
               (catch Exception e (do (println "Lordy be!" e) "boffo") (reset! continue false))))))))

(def tasks1 {::name 1 ::num-batches 1 ::batch-size 2 ::bytes 1024 ::t-max 100 ::debug true
             ::subs [{::name 2 ::num-batches 1 ::batch-size 1 ::bytes 1024 ::t-max 100} 2]
             })


(def tasks2 {::name 1 ::num-batches 5 ::batch-size 5 ::bytes 1024 ::t-max 100
            ::subs [{::name 2 ::num-batches 2 ::batch-size 2 ::bytes 2000000 ::t-max 1000}
                   {::name 3 ::num-batches 1 ::batch-size 3 ::bytes 5000000 ::t-max 2000
                    ::subs [{::name 2 ::num-batches 3 ::batch-size 10}]
                    }]
             })


(defn taskgen [& {:keys [pNew     newMax    newMin   pStop     byteMax         batchMax   tmaxMax      maxSubs   debug]
                  :or   {pNew 0.3 newMax 10 newMin 5 pStop 0.5 byteMax 10000000 batchMax 5 tmaxMax 3000 maxSubs 4 debug false}}]
  (letfn
      [(child [idMax id->desc lineage] ;; -> [idMax id->desc child]
         (let [_ (when debug (println "(child" idMax id->desc lineage))
               available (set/difference (set (range 1 (inc idMax))) lineage)]
           ;; Re-use tasks not in our path, min and max number of tasks
           (if (and (pos? (count available)) (or (>= idMax newMax) (> (rand) pNew)))
             [idMax id->desc (rand-nth (vec available))]
             ;; Otherwise create a new routine
             (let [id           (inc idMax)
                   idMax        id
                   lineage      (conj lineage id)
                   [idMax id->desc desc subs] (if (and (>= id newMin)
                                                       (or (< (rand) pStop) (> id newMax)))
                                                [idMax id->desc 0  []] ;; no new children
                                                ;; create new children, keeping track of idMax and id->desc
                                                (loop [i (inc (rand-int maxSubs))
                                                       id->desc id->desc
                                                       desc 1
                                                       idMax idMax
                                                       subs  []]
                                                  (when debug (println "loop" i id->desc idMax subs)) 
                                                  (if (zero? i)
                                                    [idMax id->desc desc subs]
                                                    (let [[idMax id->desc c] (child idMax id->desc lineage)
                                                          cid   (if (map? c) (::name c) c)
                                                          _ (when debug (println "Sub" i ":" cid c))
                                                          desc (+ desc (get id->desc cid))]
                                                      (recur (dec i) id->desc desc idMax (conj subs c))))))
                   _ (when debug (println "New" id "<=" idMax id->desc))
                   id->desc  (assoc id->desc id desc)]
               [idMax
                id->desc
                {::name id
                 ::num-batches 1
                 ::batch-size (inc (rand-int batchMax))
                 ::bytes (inc (rand-int byteMax))
                 ::t-max (inc (rand-int tmaxMax))
                 ::subs subs
                 }]))))]
      (last (child 0 {} #{})))
  )


(defn measure [n dt task-chan & [debug]]
  (System/gc)
  (go-loop [i 0
            r []]
    (let
        [tr (async/poll! task-chan)]
      (cond
        (and (<= i n) (not tr)) ;; no limit, no result
        (let [m [i @running (mem-used) (mem-used-bean) (mem-used-pools)]
              _ (when debug (println m))
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
        {U :U ws :S V :V*} (linear/svd a)
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

