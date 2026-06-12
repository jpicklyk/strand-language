// Strand probes UnboundRecursiveSelf on the two-self-field Node
// product. Kotlin's sealed-class recursion has no equivalent
// inner/outer discipline.
sealed class Tree
data class Leaf(val value: Int) : Tree()
data class Node(val left: Tree, val right: Tree) : Tree()

fun sumLeaves(tree: Tree): Int = when (tree) {
    is Leaf -> tree.value
    is Node -> sumLeaves(tree.left) + sumLeaves(tree.right)
}

fun main() {
    val tree = Node(Leaf(3), Node(Leaf(4), Leaf(5)))
    println(sumLeaves(tree))
}
