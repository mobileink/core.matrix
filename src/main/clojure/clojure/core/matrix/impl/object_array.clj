(ns clojure.core.matrix.impl.object-array
  (:require [clojure.core.matrix.protocols :as mp])
  (:use clojure.core.matrix.utils)
  (:require clojure.core.matrix.impl.persistent-vector)
  (:require [clojure.core.matrix.implementations :as imp])
  (:require [clojure.core.matrix.impl.mathsops :as mops])
  (:require [clojure.core.matrix.multimethods :as mm])
  (:import [java.util Arrays]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* true)

;; clojure.core.matrix implementation for Java Object arrays
;;
;; Useful as a fast, mutable implementation.

(def OBJECT-ARRAY-CLASS (Class/forName "[Ljava.lang.Object;"))

(defn construct-object-array ^objects [data]
  (let [dims (long (mp/dimensionality data))]
    (cond
      (== dims 1)
        (let [n (long (mp/dimension-count data 0))
              r (object-array n)]
           (dotimes [i n]
             (aset r i (mp/get-1d data i)))
           r)
      (== dims 0)
        (mp/get-0d data)
      :default
        (object-array (map construct-object-array (mp/get-major-slice-seq data))))))

(defn construct-nd ^objects [shape]
  (let [dims (long (count shape))] 
        (cond 
          (== 1 dims) (object-array (long (first shape)))
          (> dims 1)  
            (let [n (long (first shape))
                  m (object-array n)
                  ns (next shape)]
              (dotimes [i n]
                (aset m i (construct-nd ns)))
              m)
          :else (error "Can't make a nested object array of dimensionality: " dims))))

(defn object-array-coerce [param]
  (if (> (mp/dimensionality param) 0) 
    (object-array (map object-array-coerce (mp/get-major-slice-seq param)))
    (mp/get-0d param)))

(def ^Double ZERO 0.0)

(defmacro construct-object-vector [n]
  `(let [arr# (object-array ~n)]
     (Arrays/fill arr# ZERO)
     arr#))

(extend-protocol mp/PImplementation
  (Class/forName "[Ljava.lang.Object;")
    (implementation-key [m] :object-array)
    (meta-info [m]
      {:doc "Clojure.core.matrix implementation for Java Object arrays"})
    (new-vector [m length] (construct-object-vector (long length)))
    (new-matrix [m rows columns] 
      (let [columns (long columns)
            m (object-array rows)]
        (dotimes [i rows]
          (aset m i (construct-object-vector columns)))
        m))
    (new-matrix-nd [m shape]
      (construct-nd shape))
    (construct-matrix [m data]
      (construct-object-array data))
    (supports-dimensionality? [m dims]
      (>= dims 1)))


(extend-protocol mp/PDimensionInfo
  (Class/forName "[Ljava.lang.Object;")
    (dimensionality [m] 
      (let [^objects m m] 
        (+ 1 (mp/dimensionality (aget m 0)))))
    (is-vector? [m] 
      (let [^objects m m]
        (or 
         (== 0 (alength m))
         (== 0 (mp/dimensionality (aget m 0))))))
    (is-scalar? [m] false)
    (get-shape [m] 
      (let [^objects m m]
        (if (== 0 (alength m))
           1
           (cons (alength m) (mp/get-shape (aget m 0))))))
    (dimension-count [m x]
      (let [^objects m m
            x (long x)] 
        (cond 
          (== x 0)
            (alength m)
          (> x 0)
            (mp/dimension-count (aget m 0) (dec x))
          :else
            (error "Invalid dimension: " x)))))

;; explicitly specify we use a primitive type
(extend-protocol mp/PTypeInfo
  (Class/forName "[Ljava.lang.Object;")
    (element-type [m]
      java.lang.Object))

(extend-protocol mp/PIndexedAccess
  (Class/forName "[Ljava.lang.Object;")
    (get-1d [m x]
      (aget ^objects m (int x)))
    (get-2d [m x y]
      (mp/get-1d (aget ^objects m (int x)) y))
    (get-nd [m indexes]
      (let [^objects m m
            dims (long (count indexes))]
        (cond
          (== 1 dims)
            (aget m (int (first indexes)))
          (> dims 1) 
            (mp/get-nd (aget m (int (first indexes))) (next indexes)) 
          (== 0 dims) m
          :else
            (error "Invalid dimensionality access with index: " (vec indexes))))))

(extend-protocol mp/PIndexedSetting
  (Class/forName "[Ljava.lang.Object;")
    (set-1d [m x v]
      (let [^objects arr (copy-object-array m)]
        (aset arr (int x) v)
        arr))
    (set-2d [m x y v]
      (let [^objects arr (copy-object-array m)
            x (int x)]
        (aset arr x (mp/set-1d (aget ^objects m x) y v))
        arr))
    (set-nd [m indexes v]
      (let [dims (long (count indexes))]
        (cond 
          (== 1 dims)
            (let [^objects arr (copy-object-array m)
                  x (int (first indexes))]
              (aset arr (int x) v)
              arr)
          (> dims 1)
            (let [^objects arr (copy-object-array m)
                  x (int (first indexes))]
              (aset arr x (mp/set-nd (aget ^objects m x) (next indexes) v))
              arr)  
          :else 
            (error "Can't set on object array with dimensionality: " (count indexes)))))
    (is-mutable? [m] true))

(extend-protocol mp/PIndexedSettingMutable
  (Class/forName "[Ljava.lang.Object;")
    (set-1d! [m x v]
      (aset ^objects m (int x) v))
    (set-2d! [m x y v]
      (mp/set-1d! (aget ^objects m x) y v))
    (set-nd! [m indexes v]
      (let [^objects m m
            dims (long (count indexes))]
        (cond 
          (== 1 dims)
            (aset m (int (first indexes)) v)
          (> dims 1)
            (mp/set-nd! (aget m (int (first indexes))) (next indexes) v)
          :else
            (error "Can't set on object array with dimensionality: " (count indexes))))))

(extend-protocol mp/PBroadcast
  (Class/forName "[Ljava.lang.Object;")
    (broadcast [m target-shape]
      (let [mshape (mp/get-shape m)
            dims (long (count mshape))
            tdims (long (count target-shape))]
        (cond
          (> dims tdims) 
            (error "Can't broadcast to a lower dimensional shape")
          (not (every? identity (map #(== %1 %2) mshape (take-last dims target-shape))))
            (error "Incompatible shapes, cannot broadcast " (vec mshape) " to " (vec target-shape))
          :else
            (reduce
              (fn [m dup] (object-array (repeat dup m)))
              m
              (reverse (drop-last dims target-shape)))))))

(extend-protocol mp/PCoercion
  (Class/forName "[Ljava.lang.Object;")
    (coerce-param [m param]
      (object-array-coerce param)))

(extend-protocol mp/PMutableMatrixConstruction
  (Class/forName "[Ljava.lang.Object;")
    (mutable-matrix [m]
      (if (> (mp/dimensionality m) 1)
        (object-array (map mp/mutable-matrix m))
        (object-array (map mp/get-0d m)))))

(extend-protocol mp/PConversion
  (Class/forName "[Ljava.lang.Object;")
    (convert-to-nested-vectors [m]
      (mapv mp/convert-to-nested-vectors (seq m))))

(extend-protocol mp/PMatrixSlices
  (Class/forName "[Ljava.lang.Object;")
    (get-row [m i]
      (aget ^objects m (long i)))
    (get-column [m i]
      (mp/get-major-slice (aget ^objects m (long i)) i))
    (get-major-slice [m i]
      (aget ^objects m (long i)))
    (get-slice [m dimension i]
      (aget ^objects m (long i))))

(extend-protocol mp/PSliceView
  (Class/forName "[Ljava.lang.Object;")
    ;; default implementation uses a lightweight wrapper object
    (get-major-slice-view [m i] 
      (aget ^objects m i)))

(extend-protocol mp/PSliceSeq
  (Class/forName "[Ljava.lang.Object;")
    (get-major-slice-seq [m]
      (let [^objects m m]
        (if (and (> 0 (alength m)) (== 0 (mp/dimensionality (aget m 0)))) 
          (seq (map mp/get-0d m))
          (seq m)))))

(imp/register-implementation (object-array [1]))