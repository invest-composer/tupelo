;   Copyright (c) Alan Thompson. All rights reserved.
;   The use and distribution terms for this software are covered by the Eclipse Public License 1.0
;   (http://opensource.org/licenses/eclipse-1.0.php) which can be found in the file epl-v10.html at
;   the root of this distribution.  By using this software in any fashion, you are agreeing to be
;   bound by the terms of this license.  You must not remove this notice, or any other, from this
;   software.
(ns tupelo.data
  (:refer-clojure :exclude [load ->VecNode])
  (:use tupelo.core) ; #todo remove for cljs
  #?(:clj (:require
            [tupelo.core :as t :refer [spy spyx spyxx spyx-pretty grab glue map-entry indexed
                                       forv vals->map fetch-in let-spy xfirst xsecond xthird xlast xrest
                                       keep-if drop-if
                                       ]]
            [tupelo.data.index :as index]
            [tupelo.schema :as tsk]
            [tupelo.vec :as tv]
            [clojure.set :as set]
            [schema.core :as s]
            ))
  #?(:cljs (:require
             [tupelo.core :as t :refer [spy spyx spyxx spyx-pretty grab]] ; #todo :include-macros true
             [tupelo.schema :as tsk]
             [tupelo.data.index :as index]
             [clojure.set :as set]
             [schema.core :as s]
             ))
  )

; #todo add indexes
; #todo add sets (primative only or EID) => map with same key/value
; #todo copy destruct syntax for search

#?(:cljs (enable-console-print!))

; #todo Tupelo Data Language (TDL)

(def customers ; #todo be able to process this data & delete unwise users
  [{:customer-id 1
    :plants      [{:plant-id  1
                   :employees [{:name "Alice" :age 35 :sex "F"}
                               {:name "Bob" :age 25 :sex "M"}]}
                  {:plant-id  2
                   :employees []}]}
   {:customer-id 2}])
(def age-of-wisdom 30)

;---------------------------------------------------------------------------------------------------
(do       ; keep these in sync
  (def EidType
    "The Plumatic Schema type name for a pointer to a tdb node (abbrev. for Hex ID)"
    s/Int)
  (s/defn eid? :- s/Bool
    "Returns true iff the arg type is a legal EID value"
    [arg] (int? arg)))

(do       ; keep these in sync
  (def AttrType
    "The Plumatic Schema type name for an attribute"
    (s/cond-pre s/Keyword s/Int))
  (s/defn attr? :- s/Bool
    "Returns true iff the arg type is a legal attribute value"
    [arg] (or (keyword? arg) (int? arg))))

(do       ; keep these in sync
  (s/defn leaf-val? :- s/Bool
    "Returns true iff a value is of leaf type (number, string, keyword, nil)"
    [arg :- s/Any] (or (nil? arg) (number? arg) (string? arg) (keyword? arg)))
  (def LeafType (s/maybe (s/cond-pre s/Num s/Str s/Keyword)))) ; instant, uuid, Time ID (TID) (as strings?)

(s/defn array-like? :- s/Bool
  "Returns true for vectors, lists, and seq's."
  [arg] (or (vector? arg) (list? arg) (seq? arg)))

 ; #todo add tsk/Set
(do       ; keep these in sync
  (def EntityType (s/cond-pre tsk/Map tsk/Vec))
  (s/defn entity-like? [arg] (or (map? arg) (array-like? arg))) )

(def TripleIndex #{tsk/Triple})
;-----------------------------------------------------------------------------

(defprotocol IRaw
  (raw [this]))

(s/defrecord Eid ; wraps an Entity Id (EID)
  [eid :- (s/maybe EidType)]
  IRaw
  (raw [this] eid))

(s/defrecord Attr ; wraps an attribute
  [attr :- AttrType]
  IRaw
  (raw [this] attr))

(s/defrecord Leaf ; wraps a primitive leaf value
  [leaf :- (s/maybe LeafType)]
  IRaw
  (raw [this] leaf))

(s/defrecord Param ; wraps a search variable
  [param :- s/Symbol]
  IRaw
  (raw [this] param))
(defn param? [x] (instance? Param x))

;-----------------------------------------------------------------------------
(def ^:dynamic ^:no-doc *tdb* nil)

(defmacro with-tdb ; #todo swap names?
  [tdb-arg & forms]
  `(binding [*tdb* (atom ~tdb-arg)]
     ~@forms))

(defn new-tdb
  "Returns a new, empty db."
  []
  (into (sorted-map)
    {:eid-type {} ;(sorted-map) ; source type of entity (:map :array :set)
     :idx-eav  (index/empty-index)
     :idx-vae  (index/empty-index)
     :idx-ave  (index/empty-index)
     }))

(def ^:no-doc eid-count-base 1000)
(def ^:no-doc eid-counter (atom eid-count-base))

(defn ^:no-doc eid-count-reset
  "Reset the eid-count to its initial value"
  [] (reset! eid-counter eid-count-base))

(s/defn ^:no-doc new-eid :- EidType
  "Returns the next integer EID"
  [] (swap! eid-counter inc))

(s/defn add-edn :- Eid ; EidType ; #todo maybe rename:  load-edn->eid  ???
  "Add the EDN arg to the indexes, returning the EID"
  ([edn-in :- s/Any]
   (when-not (entity-like? edn-in)
     (throw (ex-info "invalid edn-in" (vals->map edn-in))))
   (let [eid-this (->Eid (new-eid))
         ctx      (cond
                    (map? edn-in)        {:entity-type :map   :edn-use edn-in}
                    (array-like? edn-in) {:entity-type :array :edn-use (indexed edn-in)}
                    :else (throw (ex-info "unknown value found" (vals->map edn-in))))]
     (t/with-map-vals ctx [entity-type edn-use]
       ; #todo could switch to transients & reduce here in a single swap
       (swap! *tdb* update :eid-type assoc eid-this entity-type )
       (doseq [[attr-edn val-edn] edn-use]
         (let [attr-rec (->Attr attr-edn)
               val-rec (if (leaf-val? val-edn)
                         (->Leaf val-edn)
                         (add-edn val-edn))]
           (swap! *tdb* update :idx-eav index/add-entry [eid-this attr-rec val-rec])
           (swap! *tdb* update :idx-vae index/add-entry [val-rec attr-rec eid-this])
           (swap! *tdb* update :idx-ave index/add-entry [attr-rec val-rec eid-this]))))
     eid-this)))

; #todo need to handle sets
(s/defn eid->edn :- s/Any
  "Returns the EDN subtree rooted at a eid."
  [eid-in :- Eid]
  (let [eav-matches (index/prefix-matches [eid-in] (grab :idx-eav @*tdb*))
        result-map  (apply glue
                      (forv [[eid-row attr-row val-row] eav-matches]
                       ;(spyx [eid-row attr-row val-row])
                        (assert (= eid-in eid-row)) ; verify is a prefix match
                        (let [attr-edn (raw attr-row) ; (if (instance? Attr attr-row)
                              val-edn  (if (instance? Leaf val-row)
                                         (raw val-row) ; Leaf rec
                                         (eid->edn val-row))] ; Eid rec
                          (t/map-entry attr-edn val-edn))))
        result-out  (let [entity-type (fetch-in @*tdb* [:eid-type eid-in])]
                      (cond
                        (= entity-type :map) result-map

                        (= entity-type :array) (let [result-keys (keys result-map)
                                                     result-vals (vec (vals result-map))]
                                                 ; if array entity, keys should be in 0..N-1
                                                 (assert (= result-keys (range (count result-keys))))
                                                 result-vals)
                        :else (throw (ex-info "invalid entity type found" (vals->map entity-type)))))]
    result-out))

(s/defn lookup :- TripleIndex ; #todo maybe use :unk or :* for unknown?
  "Given a triple of [e a v] values, use the best index to find a matching subset, where
  'nil' represents unknown values. Returns an index in [e a v] format."
  [triple :- tsk/Triple]
  (let [[e a v] triple
        not-nil-flg          (fn [arg] (if (nil? arg) 0 1))
        known-flgs           (mapv not-nil-flg triple)
        found-entries        (cond
                               (= known-flgs [1 0 0]) (let [entries (index/prefix-matches [e] (grab :idx-eav @*tdb*))
                                                            result  {:e-vals (mapv xfirst entries)
                                                                     :a-vals (mapv xsecond entries)
                                                                     :v-vals (mapv xthird entries)}]
                                                        result)
                               (= known-flgs [0 1 0]) (let [entries (index/prefix-matches [a] (grab :idx-ave @*tdb*))
                                                            result  {:a-vals (mapv xfirst entries)
                                                                     :v-vals (mapv xsecond entries)
                                                                     :e-vals (mapv xthird entries)}]
                                                        result)

                               (= known-flgs [0 0 1]) (let [entries (index/prefix-matches [v] (grab :idx-vae @*tdb*))
                                                            result  {:v-vals (mapv xfirst entries)
                                                                     :a-vals (mapv xsecond entries)
                                                                     :e-vals (mapv xthird entries)}]
                                                        result)

                               (= known-flgs [1 1 0]) (let [entries (index/prefix-matches [e a] (grab :idx-eav @*tdb*))
                                                            result  {:e-vals (mapv xfirst entries)
                                                                     :a-vals (mapv xsecond entries)
                                                                     :v-vals (mapv xthird entries)}]
                                                        result)

                               (= known-flgs [0 1 1]) (let [entries (index/prefix-matches [a v] (grab :idx-ave @*tdb*))
                                                            result  {:a-vals (mapv xfirst entries)
                                                                     :v-vals (mapv xsecond entries)
                                                                     :e-vals (mapv xthird entries)}]
                                                        result)

                               (= known-flgs [1 0 1]) (let [entries-e  (index/prefix-matches [e] (grab :idx-eav @*tdb*))
                                                            entries-ev (keep-if #(= v (xlast %)) entries-e)
                                                            result     {:e-vals (mapv xfirst entries-ev)
                                                                        :a-vals (mapv xsecond entries-ev)
                                                                        :v-vals (mapv xthird entries-ev)}]
                                                        result)
                               :else (throw (ex-info "invalid known-flags" (vals->map triple known-flgs))))
        result-index         (t/with-map-vals found-entries [e-vals a-vals v-vals]
                               (index/->index (map vector e-vals a-vals v-vals)))]
    result-index))

(s/defn apply-env
  [env :- tsk/Map
   listy :- tsk/Vec]
  (forv [elem listy]
    (if (contains? env elem) ; #todo make sure works witn `nil` value
      (get env elem)
      elem)))

(s/defn query-impl :- s/Any
  [ctx :- tsk/KeyMap]
  (nl)
  (let [  ; #todo => with-map-vals
        env        (grab :env ctx)
        qspec-list (grab :qspec-list ctx)
        query-result (grab :query-result ctx)]
    (if (empty? qspec-list)
      (swap! query-result  t/append env)
      (let [qspec-curr         (xfirst qspec-list)
            qspec-rest         (xrest qspec-list)
            qspec-curr-env     (apply-env env qspec-curr)
            >>                 (spyx qspec-curr)
            >>                 (spyx qspec-curr-env)

            {idxs-param :idxs-true
             idxs-other :idxs-false} (tv/pred-index param? qspec-curr-env)

            qspec-lookup       (tv/set-lax qspec-curr-env idxs-param nil)
            >>                 (spyx qspec-lookup)
            params             (tv/get qspec-curr idxs-param)
            found-triples      (lookup qspec-lookup)
            param-frames-found (mapv #(tv/get % idxs-param) found-triples)
            env-frames-found   (mapv #(zipmap params %) param-frames-found)]
        (spyx idxs-param)
        (spyx idxs-other)
        (spyx-pretty found-triples)
        (spyx-pretty param-frames-found)
        (spyx-pretty env-frames-found)
        (forv [env-frame env-frames-found]
          (let [env-next (glue env env-frame)]
            (query-impl (t/glue ctx {:qspec-list qspec-rest
                                     :env        env-next}))))))))

(s/defn query
  [qspec-list :- [tsk/Triple]]
  (let [query-result (atom [])]
    (query-impl {:query-result query-result
                 :qspec-list   qspec-list
                 :env          {}})
    @query-result))


;(s/defn index-find-leaf
;  [target :- LeafType]
;  (let [idx-entries (index-find-val-impl [target])
;        hids        (mapv t/xsecond idx-entries)]
;    hids))
;
;(s/defn index-find-mapentry :- [EidType]
;  [tgt-me :- tsk/MapEntry]
;  (let [[tgt-key tgt-val] (mapentry->kv tgt-me)
;        tgt-prefix       [tgt-val tgt-key]
;        idx-avl-set      (t/validate set? (fetch-in (deref *tdb*) [:idx-map-entry-vk]))
;        matching-entries (grab :matches
;                           (index/split-key-prefix tgt-prefix idx-avl-set))
;        men-hids         (mapv xlast matching-entries)
;        ]
;    men-hids))
;
;(s/defn index-find-submap
;  [target-submap :- tsk/KeyMap]
;  (let [map-hids (apply set/intersection
;                   (forv [tgt-me target-submap]
;                     (set (mapv hid->parent-hid
;                            (index-find-mapentry tgt-me)))))]
;    map-hids))
;
;(s/defn index-find-mapentry-key :- [EidType]
;  [tgt-key :- LeafType]
;  (let [tgt-prefix       [tgt-key]
;        index            (t/validate set? (fetch-in (deref *tdb*) [:idx-map-entry-kv]))
;        matching-entries (grab :matches
;                           (index/split-key-prefix tgt-prefix index))
;        men-hids         (mapv xlast matching-entries)
;        ]
;    men-hids))
;
;(s/defn index-find-arrayentry :- [EidType]
;  [tgt-ae :- tsk/MapEntry] ; {idx elem} as a MapEntry
;  (let [[tgt-idx tgt-elem] (mapentry->kv tgt-ae)
;        tgt-prefix       [tgt-elem tgt-idx]
;        index            (t/validate set? (fetch-in (deref *tdb*) [:idx-array-entry-ei]))
;        matching-entries (grab :matches
;                           (index/split-key-prefix tgt-prefix index))
;        aen-hids         (mapv xlast matching-entries)
;        ]
;    aen-hids))
;
;(s/defn index-find-arrayentry-idx :- [EidType]
;  [tgt-idx :- LeafType]
;  (let [tgt-prefix       [tgt-idx]
;        index            (t/validate set? (fetch-in (deref *tdb*) [:idx-array-entry-ie]))
;        matching-entries (grab :matches
;                           (index/split-key-prefix tgt-prefix index))
;        aen-hids         (mapv xlast matching-entries)
;        ]
;    aen-hids))
;
;(s/defn parent-path-hid :- [EidType]
;  [hid-in :- EidType]
;  (let [node-in (hid->node hid-in)]
;    (loop [result   (cond
;                      (instance? MapEntryNode node-in) [hid-in (me-val-hid node-in)]
;                      (instance? ArrayEntryNode node-in) [hid-in (ae-elem-hid node-in)]
;                      (instance? LeafNode node-in) [hid-in]
;                      :else (throw (ex-info "unrecognized node type" (vals->map hid-in node-in))))
;           hid-curr hid-in]
;      (let [hid-par (parent-hid (hid->node hid-curr))]
;        (if (nil? hid-par)
;          result
;          (if (or
;                (instance? MapEntryNode (hid->node hid-par))
;                (instance? ArrayEntryNode (hid->node hid-par)))
;            (recur (t/prepend hid-par result) hid-par)
;            (recur result hid-par)))))))
;
;(s/defn parent-path-vals
;  [hid-in :- EidType]
;  (let [path-hids        (parent-path-hid hid-in)
;        parent-selectors (forv [path-hid path-hids]
;                           (let [path-node (hid->node path-hid)]
;                             (cond
;                               (instance? MapEntryNode path-node) (me-key path-node)
;                               (instance? ArrayEntryNode path-node) (ae-idx path-node)
;                               (instance? LeafNode path-node) (edn path-node)
;                               :else (throw (ex-info "invalid parent node" (vals->map path-node))))))]
;    parent-selectors))


;(def idx-prefix-lookup
;  (index/->index [[:e :a :v :idx-eav]
;                  [:v :a :e :idx-vae]
;                  [:a :v :e :idx-ave]]))

























