/*
 * Auto-generated AIDL stub (hand-written for Termux aarch64 compatibility).
 * Equivalent to compiling IWhisperService.aidl
 */
package space.manus.nacre.ai;

public interface IWhisperService extends android.os.IInterface {

    boolean isModelLoaded() throws android.os.RemoteException;
    boolean isRecognizing() throws android.os.RemoteException;
    void loadModel(String modelPath) throws android.os.RemoteException;
    void unloadModel() throws android.os.RemoteException;
    void startRecognition(String language, IWhisperCallback callback) throws android.os.RemoteException;
    void stopRecognition() throws android.os.RemoteException;
    void startContinuousRecognition(String language, IWhisperCallback callback) throws android.os.RemoteException;
    void cancelContinuousRecognition() throws android.os.RemoteException;

    public static class Default implements IWhisperService {
        @Override public boolean isModelLoaded() throws android.os.RemoteException { return false; }
        @Override public boolean isRecognizing() throws android.os.RemoteException { return false; }
        @Override public void loadModel(String modelPath) throws android.os.RemoteException {}
        @Override public void unloadModel() throws android.os.RemoteException {}
        @Override public void startRecognition(String language, IWhisperCallback callback) throws android.os.RemoteException {}
        @Override public void stopRecognition() throws android.os.RemoteException {}
        @Override public void startContinuousRecognition(String language, IWhisperCallback callback) throws android.os.RemoteException {}
        @Override public void cancelContinuousRecognition() throws android.os.RemoteException {}
        @Override public android.os.IBinder asBinder() { return null; }
    }

    public static abstract class Stub extends android.os.Binder implements IWhisperService {

        private static final String DESCRIPTOR = "space.manus.nacre.ai.IWhisperService";
        static final int TRANSACTION_isModelLoaded = android.os.IBinder.FIRST_CALL_TRANSACTION + 0;
        static final int TRANSACTION_isRecognizing = android.os.IBinder.FIRST_CALL_TRANSACTION + 1;
        static final int TRANSACTION_loadModel = android.os.IBinder.FIRST_CALL_TRANSACTION + 2;
        static final int TRANSACTION_unloadModel = android.os.IBinder.FIRST_CALL_TRANSACTION + 3;
        static final int TRANSACTION_startRecognition = android.os.IBinder.FIRST_CALL_TRANSACTION + 4;
        static final int TRANSACTION_stopRecognition = android.os.IBinder.FIRST_CALL_TRANSACTION + 5;
        static final int TRANSACTION_startContinuousRecognition = android.os.IBinder.FIRST_CALL_TRANSACTION + 6;
        static final int TRANSACTION_cancelContinuousRecognition = android.os.IBinder.FIRST_CALL_TRANSACTION + 7;

        public Stub() { this.attachInterface(this, DESCRIPTOR); }

        public static IWhisperService asInterface(android.os.IBinder obj) {
            if (obj == null) return null;
            android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof IWhisperService) return (IWhisperService) iin;
            return new Proxy(obj);
        }

        @Override public android.os.IBinder asBinder() { return this; }

        @Override
        public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException {
            switch (code) {
                case TRANSACTION_isModelLoaded: {
                    data.enforceInterface(DESCRIPTOR);
                    boolean _result = this.isModelLoaded();
                    reply.writeNoException();
                    reply.writeInt(_result ? 1 : 0);
                    return true;
                }
                case TRANSACTION_isRecognizing: {
                    data.enforceInterface(DESCRIPTOR);
                    boolean _result = this.isRecognizing();
                    reply.writeNoException();
                    reply.writeInt(_result ? 1 : 0);
                    return true;
                }
                case TRANSACTION_loadModel: {
                    data.enforceInterface(DESCRIPTOR);
                    String _arg0 = data.readString();
                    this.loadModel(_arg0);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_unloadModel: {
                    data.enforceInterface(DESCRIPTOR);
                    this.unloadModel();
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_startRecognition: {
                    data.enforceInterface(DESCRIPTOR);
                    String _arg0 = data.readString();
                    IWhisperCallback _arg1 = IWhisperCallback.Stub.asInterface(data.readStrongBinder());
                    this.startRecognition(_arg0, _arg1);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_stopRecognition: {
                    data.enforceInterface(DESCRIPTOR);
                    this.stopRecognition();
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_startContinuousRecognition: {
                    data.enforceInterface(DESCRIPTOR);
                    String _arg0 = data.readString();
                    IWhisperCallback _arg1 = IWhisperCallback.Stub.asInterface(data.readStrongBinder());
                    this.startContinuousRecognition(_arg0, _arg1);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_cancelContinuousRecognition: {
                    data.enforceInterface(DESCRIPTOR);
                    this.cancelContinuousRecognition();
                    reply.writeNoException();
                    return true;
                }
            }
            return super.onTransact(code, data, reply, flags);
        }

        private static class Proxy implements IWhisperService {
            private final android.os.IBinder mRemote;
            Proxy(android.os.IBinder remote) { mRemote = remote; }
            @Override public android.os.IBinder asBinder() { return mRemote; }

            @Override
            public boolean isModelLoaded() throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                android.os.Parcel _reply = android.os.Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_isModelLoaded, _data, _reply, 0);
                    _reply.readException();
                    return _reply.readInt() != 0;
                } finally { _reply.recycle(); _data.recycle(); }
            }

            @Override
            public boolean isRecognizing() throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                android.os.Parcel _reply = android.os.Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_isRecognizing, _data, _reply, 0);
                    _reply.readException();
                    return _reply.readInt() != 0;
                } finally { _reply.recycle(); _data.recycle(); }
            }

            @Override
            public void loadModel(String modelPath) throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                android.os.Parcel _reply = android.os.Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(modelPath);
                    mRemote.transact(TRANSACTION_loadModel, _data, _reply, 0);
                    _reply.readException();
                } finally { _reply.recycle(); _data.recycle(); }
            }

            @Override
            public void unloadModel() throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                android.os.Parcel _reply = android.os.Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_unloadModel, _data, _reply, 0);
                    _reply.readException();
                } finally { _reply.recycle(); _data.recycle(); }
            }

            @Override
            public void startRecognition(String language, IWhisperCallback callback) throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                android.os.Parcel _reply = android.os.Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(language);
                    _data.writeStrongBinder(callback != null ? callback.asBinder() : null);
                    mRemote.transact(TRANSACTION_startRecognition, _data, _reply, 0);
                    _reply.readException();
                } finally { _reply.recycle(); _data.recycle(); }
            }

            @Override
            public void stopRecognition() throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                android.os.Parcel _reply = android.os.Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_stopRecognition, _data, _reply, 0);
                    _reply.readException();
                } finally { _reply.recycle(); _data.recycle(); }
            }

            @Override
            public void startContinuousRecognition(String language, IWhisperCallback callback) throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                android.os.Parcel _reply = android.os.Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(language);
                    _data.writeStrongBinder(callback != null ? callback.asBinder() : null);
                    mRemote.transact(Stub.TRANSACTION_startContinuousRecognition, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void cancelContinuousRecognition() throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                android.os.Parcel _reply = android.os.Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(Stub.TRANSACTION_cancelContinuousRecognition, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }
        }
    }
}
