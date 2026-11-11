package io.github.truscdo.foldbackup.core.store;

import java.io.IOException;

/**
 * 仓库写锁在限定时间内没有获得：仓库正被另一个进程（或本进程的另一个实例）占用。
 * <p>
 * 与其它 {@link IOException} 的区别在于<b>可重试</b>：占锁的一方总会结束并释放锁，
 * 因此调用方可以过一会再试，而不应据此把整个后端判为不可用。
 */
public class RepositoryBusyException extends IOException {

    public RepositoryBusyException(String message) {
        super(message);
    }
}
