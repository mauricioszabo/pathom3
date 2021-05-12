(ns com.wsscode.pathom3.connect.runner.stats
  (:require
    [com.fulcrologic.guardrails.core :refer [<- => >def >defn >fdef ? |]]
    [com.wsscode.pathom3.attribute :as p.attr]
    [com.wsscode.pathom3.connect.built-in.resolvers :as pbir]
    [com.wsscode.pathom3.connect.indexes :as pci]
    [com.wsscode.pathom3.connect.operation :as pco]
    [com.wsscode.pathom3.connect.planner :as pcp]
    [com.wsscode.pathom3.connect.runner :as pcr]))

(>def ::node-error-id ::pcp/node-id)

(>def ::node-error-type #{::node-error-type-direct
                          ::node-error-type-ancestor})

; region performance

(defn duration-resolver [attr]
  (let [op-name     (symbol (str (pbir/attr-munge attr) "-duration"))
        start-kw    (keyword (namespace attr) (str (name attr) "-start-ms"))
        finish-kw   (keyword (namespace attr) (str (name attr) "-finish-ms"))
        duration-kw (keyword (namespace attr) (str (name attr) "-duration-ms"))]
    (pco/resolver op-name
      {::pco/input  [start-kw finish-kw]
       ::pco/output [duration-kw]}
      (fn [_ input]
        {duration-kw (- (finish-kw input) (start-kw input))}))))

(pco/defresolver resolver-accumulated-duration
  [{::pcr/keys [node-run-stats]} _]
  {::resolver-accumulated-duration-ms
   (transduce (map #(- (::pcr/resolver-run-finish-ms %)
                       (::pcr/resolver-run-start-ms %))) + 0 (vals node-run-stats))})

(pco/defresolver overhead-duration
  [{::pcr/keys [graph-run-duration-ms]
    ::keys     [resolver-accumulated-duration-ms]}]
  {::overhead-duration-ms
   (- graph-run-duration-ms resolver-accumulated-duration-ms)})

(pco/defresolver overhead-pct
  [{::pcr/keys [graph-run-duration-ms]
    ::keys     [overhead-duration-ms]}]
  {::overhead-duration-percentage
   (double (/ overhead-duration-ms graph-run-duration-ms))})

; endregion

; region errors

(defn node-attribute-error
  [{::pcr/keys [node-run-stats] :as env} node-id]
  (let [error (get-in node-run-stats [node-id ::pcr/node-error])]
    (if error
      {::attribute-error
       {::node-error-type ::node-error-type-direct
        ::pcr/node-error  error}}

      (if-let [[nid error] (->> (pcp/node-ancestors env node-id)
                                (some #(if-let [err (get-in node-run-stats [% ::pcr/node-error])]
                                         [% err])))]
        {::attribute-error
         {::node-error-type ::node-error-type-ancestor
          ::node-error-id   nid
          ::pcr/node-error  error}}))))

(pco/defresolver attribute-error
  "Find the error for a node, it first try to find the error in the node itself, but
  also walks up the graph to collect errors on previous nodes."
  [{::pcp/keys [index-attrs available-data] :as env}
   {::p.attr/keys [attribute]}]
  {::pco/output [::attribute-error]}
  (if-let [node-ids (get index-attrs attribute)]
    (some #(node-attribute-error env %) node-ids)
    (if-not (contains? available-data attribute)
      {::attribute-error
       (if (-> env ::pcr/env ::pci/index-attributes (contains? attribute))
         {::node-error-type ::node-error-type-unreachable
          ::pcr/node-error  (ex-info (str "Attribute " attribute " is indexed but can't be reached with current data.")
                                     {::p.attr/attribute attribute})}
         {::node-error-type ::node-error-type-unavailable
          ::pcr/node-error  (ex-info (str "Attribute " attribute " is not available in this index.")
                                     {::p.attr/attribute attribute})})})))

; endregion

(def stats-registry
  [resolver-accumulated-duration
   overhead-duration
   overhead-pct
   attribute-error
   (pbir/alias-resolver ::pcr/compute-plan-run-start-ms ::pcr/process-run-start-ms)
   (pbir/alias-resolver ::pcr/graph-run-finish-ms ::pcr/process-run-finish-ms)
   (duration-resolver ::pcr/process-run)
   (duration-resolver ::pcr/node-run)
   (duration-resolver ::pcr/resolver-run)
   (duration-resolver ::pcr/batch-run)
   (duration-resolver ::pcr/graph-run)
   (duration-resolver ::pcr/compute-plan-run)
   (duration-resolver ::pcr/mutation-run)
   (pbir/single-attr-with-env-resolver ::p.attr/attribute ::pcp/node-id
     #(get (::pcp/index-attrs %) %2 ::pco/unknown-value))
   (pbir/env-table-resolver ::pcp/nodes ::pcp/node-id
     [::pco/op-name
      ::pcp/expects
      ::pcp/input
      ::pcp/run-and
      ::pcp/run-or
      ::pcp/run-next
      ::pcp/foreign-ast
      ::pcp/source-for-attrs
      ::pcp/node-parents])
   (pbir/env-table-resolver ::pcr/node-run-stats ::pcp/node-id
     [::pcr/resolver-run-start-ms
      ::pcr/resolver-run-finish-ms
      ::pcr/batch-run-start-ms
      ::pcr/batch-run-finish-ms
      ::pcp/nested-process
      ::pcr/node-run-start-ms
      ::pcr/node-run-finish-ms
      ::pcr/node-resolver-input
      ::pcr/node-resolver-input-shape
      ::pcr/node-resolver-output
      ::pcr/node-resolver-output-shape
      ::pcr/node-error
      ::pcr/taken-paths
      ::pcr/success-path])])

(def stats-index (pci/register stats-registry))

(defn run-stats-env [stats]
  (-> stats
      (pci/register stats-index)))

(defn get-attribute-error [smart-stats attr]
  (get (assoc smart-stats ::p.attr/attribute attr) ::attribute-error))
