import { SilentUpdate } from '@svssdeva/cap-silent-update';

window.testEcho = () => {
    const inputValue = document.getElementById("echoInput").value;
    SilentUpdate.echo({ value: inputValue })
}
