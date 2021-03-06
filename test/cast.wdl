import "library_math.wdl" as lib

task EmptyArray {
     Array[Int] fooAr

     command {
     }
     output {
       Array[Int] result=fooAr
     }
}


task SumArray {
    Array[Int] ints

    command <<<
        python -c "print(${sep="+" ints})"
    >>>
    output {
        Int result = read_int(stdout())
    }
}

task FileIdent {
    File aF
    command {
    }
    output {
       File result = aF
    }
}

workflow cast {
    Int i
    String s
    File foo
    Array[Int] iArr = [i]
    Array[String] sArr = [s]
    Array[File] fooArr = [foo]

    # Handling of empty arrays as input/output
    call EmptyArray { input: fooAr=[] }

    # WDL does not automatically cast from type T to Array[T]
    #Array[Int] iArr2 = i
    #Array[String] sArr2 = s
    #Array[File] fooArr2 = foo

    call FileIdent { input: aF = foo }
    File foo2 = FileIdent.result
    Array[File] fArr2 = [foo2]

    call lib.Add as Add { input: a=i, b=i }
    call SumArray {input: ints=iArr }

    # WDL casts an Int to an Array[Int] automatically
    # when it is a call argument
    Array[Int] ytmp1 = [i]
    call SumArray as SumArray2 {input: ints=ytmp1 }

    output {
       Add.result
       SumArray.result
       SumArray2.result
    }
}
