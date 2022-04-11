(ns frontend.handler.dnd
  (:require [frontend.db :as db]
            [frontend.handler.editor :as editor-handler]
            [frontend.modules.outliner.core :as outliner-core]
            [frontend.modules.outliner.tree :as tree]
            [frontend.modules.outliner.transaction :as outliner-tx]
            [frontend.state :as state]
            [frontend.util :as util]))

(defn- ancestor?
  "Whether current-block is an ancestor of the target-block."
  [current-block-uuid target-block]
  (loop [loc target-block]
    (if-let [parent (db/entity (:db/id (:block/parent loc)))]
      (if (= (:block/uuid parent) current-block-uuid)
        true
        (recur parent))
      false)))

(defn- movable?
  [current-block target-block move-to]
  (let [current-block-uuid (:block/uuid current-block)]
    (not
     (or
      (= current-block-uuid (:block/uuid target-block)) ; same block

      (ancestor? current-block-uuid target-block)

      (and (= move-to :nested)
           ;; current block is already the first child of target-block
           (= (:db/id (:block/left current-block))
              (:db/id (:block/parent current-block))
              (:db/id target-block)))

      (and (= move-to :sibling)
           ;; current block is already the next sibling of target-block
           (= (:db/id (:block/left current-block))
              (:db/id target-block))
           (not= (:db/id (:block/parent current-block))
                 (:db/id target-block)))))))

(defn move-block
  "There can be two possible situations:
  1. Move a block in the same file (either top-to-bottom or bottom-to-top).
  2. Move a block between two different files.

  move-to: :sibling :nested :top nil

  Notes:
  Sometimes we might need to move a parent block to it's own child.
  "
  [^js event current-block target-block move-to]
  (let [top? (= move-to :top)
        nested? (= move-to :nested)
        alt-key? (and event (.-altKey event))
        current-format (:block/format current-block)
        target-format (:block/format target-block)]
    (cond
      ;; alt pressed, make a block-ref
      alt-key?
      (do
        (editor-handler/set-block-property! (:block/uuid current-block)
                                            :id
                                            (str (:block/uuid current-block)))
        (editor-handler/api-insert-new-block!
         (util/format "((%s))" (str (:block/uuid current-block)))
         {:block-uuid (:block/uuid target-block)
          :sibling? (not nested?)
          :before? top?}))

      ;; format mismatch
      (and current-format target-format (not= current-format target-format))
      (state/pub-event! [:notification/show
                         {:content [:div "Those two pages have different formats."]
                          :status :warning
                          :clear? true}])


      ;; movable
      (and (every? map? [current-block target-block])
           (movable? current-block target-block move-to))
      (let [target-node (outliner-core/block target-block)]
        (outliner-tx/transact!
          {:outliner-op :move-blocks}
          (if top?
            (let [first-child?
                  (= (tree/-get-parent-id target-node)
                     (tree/-get-left-id target-node))]
              (if first-child?
                (let [parent (tree/-get-parent target-node)]
                  (outliner-core/move-blocks! [current-block] (:data parent) false))
                (let [before-node (tree/-get-left target-node)]
                  (outliner-core/move-blocks! [current-block] (:data before-node) true))))
            (outliner-core/move-blocks! [current-block] target-block (not nested?)))))

      :else
      nil)))
