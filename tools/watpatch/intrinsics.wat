;; Linear-memory intrinsics for Nmem.java (platform/web/.../mem) (see hook.mjs for the format).
;; Parameters are named $p0.. by Web Image; addresses are Java longs (i64)
;; truncated to wasm32 offsets.

;; @module
  (memory $nmem (export "nmem") 16 65536)
;; @end

;; @replace-body $_Nmem_putByte___J_B_V
    (i32.store8 $nmem (i32.wrap_i64 (local.get $p0)) (local.get $p1))
;; @end
;; @replace-body $_Nmem_getByte___J_B
    (i32.load8_s $nmem (i32.wrap_i64 (local.get $p0)))
;; @end
;; @replace-body $_Nmem_putShort___J_S_V
    (i32.store16 $nmem (i32.wrap_i64 (local.get $p0)) (local.get $p1))
;; @end
;; @replace-body $_Nmem_getShort___J_S
    (i32.load16_s $nmem (i32.wrap_i64 (local.get $p0)))
;; @end
;; @replace-body $_Nmem_putInt___J_I_V
    (i32.store $nmem (i32.wrap_i64 (local.get $p0)) (local.get $p1))
;; @end
;; @replace-body $_Nmem_getInt___J_I
    (i32.load $nmem (i32.wrap_i64 (local.get $p0)))
;; @end
;; @replace-body $_Nmem_putLong___J_J_V
    (i64.store $nmem (i32.wrap_i64 (local.get $p0)) (local.get $p1))
;; @end
;; @replace-body $_Nmem_getLong___J_J
    (i64.load $nmem (i32.wrap_i64 (local.get $p0)))
;; @end
;; @replace-body $_Nmem_putFloat___J_F_V
    (f32.store $nmem (i32.wrap_i64 (local.get $p0)) (local.get $p1))
;; @end
;; @replace-body $_Nmem_getFloat___J_F
    (f32.load $nmem (i32.wrap_i64 (local.get $p0)))
;; @end
;; @replace-body $_Nmem_putDouble___J_D_V
    (f64.store $nmem (i32.wrap_i64 (local.get $p0)) (local.get $p1))
;; @end
;; @replace-body $_Nmem_getDouble___J_D
    (f64.load $nmem (i32.wrap_i64 (local.get $p0)))
;; @end
;; @replace-body $_Nmem_copy___J_J_J_V
    (memory.copy $nmem $nmem
      (i32.wrap_i64 (local.get $p1))
      (i32.wrap_i64 (local.get $p0))
      (i32.wrap_i64 (local.get $p2)))
;; @end
;; @replace-body $_Nmem_fill___J_I_J_V
    (memory.fill $nmem
      (i32.wrap_i64 (local.get $p0))
      (local.get $p1)
      (i32.wrap_i64 (local.get $p2)))
;; @end
;; @replace-body $_Nmem_pages___I
    (memory.size $nmem)
;; @end
;; @replace-body $_Nmem_grow___I_I
    (memory.grow $nmem (local.get $p0))
;; @end

;; copyFromBytes(byte[] src, int srcOff, long dst, int len)
;; @replace-body $_Nmem_copyFromBytes___$B_I_J_I_V
    (local $i i32)
    (local $d i32)
    (local $s i32)
    (local $end4 i32)
    (local $a (ref $array.byte))
    (local.set $a (struct.get $struct.array.byte $field.inner (local.get $p0)))
    (local.set $d (i32.wrap_i64 (local.get $p2)))
    (local.set $s (local.get $p1))
    (local.set $end4 (i32.and (local.get $p3) (i32.const -4)))
    (block $done4
      (loop $next4
        (br_if $done4 (i32.ge_u (local.get $i) (local.get $end4)))
        (i32.store $nmem (i32.add (local.get $d) (local.get $i))
          (i32.or
            (i32.or
              (array.get_u $array.byte (local.get $a) (i32.add (local.get $s) (local.get $i)))
              (i32.shl (array.get_u $array.byte (local.get $a) (i32.add (local.get $s) (i32.add (local.get $i) (i32.const 1)))) (i32.const 8)))
            (i32.or
              (i32.shl (array.get_u $array.byte (local.get $a) (i32.add (local.get $s) (i32.add (local.get $i) (i32.const 2)))) (i32.const 16))
              (i32.shl (array.get_u $array.byte (local.get $a) (i32.add (local.get $s) (i32.add (local.get $i) (i32.const 3)))) (i32.const 24)))))
        (local.set $i (i32.add (local.get $i) (i32.const 4)))
        (br $next4)))
    (block $done
      (loop $next
        (br_if $done (i32.ge_u (local.get $i) (local.get $p3)))
        (i32.store8 $nmem (i32.add (local.get $d) (local.get $i))
          (array.get_u $array.byte (local.get $a) (i32.add (local.get $s) (local.get $i))))
        (local.set $i (i32.add (local.get $i) (i32.const 1)))
        (br $next)))
;; @end

;; copyToBytes(long src, byte[] dst, int dstOff, int len)
;; @replace-body $_Nmem_copyToBytes___J_$B_I_I_V
    (local $i i32)
    (local $s i32)
    (local $a (ref $array.byte))
    (local.set $a (struct.get $struct.array.byte $field.inner (local.get $p1)))
    (local.set $s (i32.wrap_i64 (local.get $p0)))
    (block $done
      (loop $next
        (br_if $done (i32.ge_u (local.get $i) (local.get $p3)))
        (array.set $array.byte (local.get $a) (i32.add (local.get $p2) (local.get $i))
          (i32.load8_u $nmem (i32.add (local.get $s) (local.get $i))))
        (local.set $i (i32.add (local.get $i) (i32.const 1)))
        (br $next)))
;; @end
